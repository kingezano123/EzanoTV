// Builds the device-check report from tools/device-check.sh output.
// Usage: node tools/device-report.cjs device-results
// Writes <out>/publish/{summary.md,junit.xml,events.txt,logcat-app.txt,instrument-output.txt,*.png} and <out>/status.txt.
const fs=require('fs'),path=require('path');
const out=path.resolve(process.argv[2]||'device-results');
const pub=path.join(out,'publish');fs.mkdirSync(pub,{recursive:true});
const read=f=>{try{return fs.readFileSync(f,'utf8')}catch{return ''}};
const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
const CODES={'0':'passed','-1':'error','-2':'failed','-3':'ignored','-4':'skipped (assumption)','-99':'did not finish'};

function parseInstrument(text){
  const tests=[];let bundle={},key=null,inResult=false,current=null;const result={};
  for(const line of text.split(/\r?\n/)){
    let m;
    if((m=/^INSTRUMENTATION_STATUS: ([\w.-]+)=(.*)$/.exec(line))){key=m[1];bundle[key]=m[2];inResult=false;continue}
    if((m=/^INSTRUMENTATION_STATUS_CODE: (-?\d+)/.exec(line))){
      const code=m[1];
      if(code==='1')current={cls:bundle.class,name:bundle.test};
      else{tests.push({cls:bundle.class||current?.cls,name:bundle.test||current?.name,code,stack:(bundle.stack||'').trim()});current=null}
      bundle={};key=null;continue;
    }
    if((m=/^INSTRUMENTATION_RESULT: ([\w.-]+)=(.*)$/.exec(line))){inResult=true;key=m[1];result[key]=m[2];continue}
    if((m=/^INSTRUMENTATION_CODE: (-?\d+)/.exec(line))){result.code=m[1];key=null;continue}
    if(key){if(inResult)result[key]+='\n'+line;else bundle[key]+='\n'+line}
  }
  if(current)tests.push({...current,code:'-99',stack:`Instrumentation ended before the test finished: ${result.shortMsg||'(no message)'}\n${result.longMsg||''}`.trim()});
  return {tests,result};
}

const steps=read(path.join(out,'steps.tsv')).split('\n').filter(Boolean).map(l=>{const [name,desc,args,exit,secs]=l.split('\t');return {name,desc,args,exit,secs:+secs}});
const all=[];const events=[];const metrics=[];const crashes=[];let logcatApp='';let rawAll='';
for(const step of steps){
  const raw=read(path.join(out,'raw',step.name+'.txt'));rawAll+=`\n===== ${step.name}: ${step.desc}\n$ am instrument -r -w ${step.args}\n${raw}`;
  const {tests,result}=parseInstrument(raw);
  if(tests.length===0){
    const tail=raw.trim().split('\n').slice(-25).join('\n');
    tests.push({cls:(/class ([\w.#]+)/.exec(step.args)||[])[1]||step.name,name:'(no test ran)',code:'-99',stack:`No test result. exit=${step.exit} ${result.shortMsg||''}\n${result.longMsg||''}\n${tail}`.trim()});
  }
  for(const t of tests)all.push({...t,step});
  const log=read(path.join(out,'logcat',step.name+'.txt')).split(/\r?\n/);
  const pids=new Set();
  for(const l of log){const m=/Start proc (\d+):tv\.ezano\.app/.exec(l);if(m)pids.add(m[1])}
  const keep=[];
  for(let i=0;i<log.length;i++){
    const l=log[i];const dc=/ DeviceCheck: (\w+) (.*)$/.exec(l);
    if(dc){events.push({step:step.name,kind:dc[1],msg:dc[2]});if(dc[1]==='METRIC')metrics.push({step:step.name,text:dc[2]})}
    const f=/^\S+\s+\S+\s+(\d+)\s+\d+\s+([VDIWEF])\s/.exec(l);
    const fromApp=f&&pids.has(f[1])&&'WEF'.includes(f[2]);
    if(fromApp||/DeviceCheck|TestRunner|AndroidRuntime|FATAL|ANR in|tv\.ezano|Choreographer.*Skipped|lowmemorykiller|am_crash|Process .* has died/.test(l))keep.push(l);
    if(/FATAL EXCEPTION|ANR in tv\.ezano|Fatal signal/.test(l))crashes.push({step:step.name,text:log.slice(i,i+14).join('\n')});
  }
  logcatApp+=`\n===== ${step.name}: ${step.desc} (${keep.length} lines, app pids ${[...pids].join(',')||'?'})\n`+keep.slice(-4000).join('\n')+'\n';
}
for(const l of read(path.join(out,'host-metrics.txt')).split('\n'))if(l.startsWith('METRIC '))metrics.push({step:'host',text:l.slice(7)});

// Screenshots: numbered device-check shots plus legacy test PNGs.
const shots=[];
const screens=path.join(out,'screens');
for(const f of (fs.existsSync(screens)?fs.readdirSync(screens):[]).sort())if(f.endsWith('.png')&&fs.statSync(path.join(screens,f)).size>0){fs.copyFileSync(path.join(screens,f),path.join(pub,f));shots.push(f)}
const filesDir=path.join(out,'files');
for(const step of (fs.existsSync(filesDir)?fs.readdirSync(filesDir):[]).sort()){
  const walk=d=>{for(const f of fs.readdirSync(d)){const p=path.join(d,f);if(fs.statSync(p).isDirectory())walk(p);else if(/\.(png|xml)$/.test(f)){const name=`${f.endsWith('.xml')?'hierarchy':'legacy'}-${step}-${f}`;fs.copyFileSync(p,path.join(pub,name));if(f.endsWith('.png'))shots.push(name)}}};
  walk(path.join(filesDir,step));
}

const failed=all.filter(t=>!['0','-3','-4'].includes(t.code));
const status=failed.length===0&&all.length>0?'pass':'fail';
fs.writeFileSync(path.join(out,'status.txt'),status+'\n');

// JUnit XML grouped by class.
const byClass={};for(const t of all)(byClass[t.cls]=byClass[t.cls]||[]).push(t);
let xml='<?xml version="1.0" encoding="UTF-8"?>\n<testsuites name="ezano-device-check">\n';
for(const [cls,ts] of Object.entries(byClass)){
  xml+=`  <testsuite name="${esc(cls)}" tests="${ts.length}" failures="${ts.filter(t=>t.code==='-2').length}" errors="${ts.filter(t=>['-1','-99'].includes(t.code)).length}" skipped="${ts.filter(t=>['-3','-4'].includes(t.code)).length}">\n`;
  for(const t of ts){
    xml+=`    <testcase classname="${esc(cls)}" name="${esc(t.name)} [${esc(t.step.name)}]">`;
    if(t.code==='-2')xml+=`<failure message="${esc(t.stack.split('\n')[0])}">${esc(t.stack)}</failure>`;
    else if(['-1','-99'].includes(t.code))xml+=`<error message="${esc(t.stack.split('\n')[0])}">${esc(t.stack)}</error>`;
    else if(['-3','-4'].includes(t.code))xml+=`<skipped message="${esc(t.stack.split('\n')[0]||'skipped')}"/>`;
    xml+='</testcase>\n';
  }
  xml+='  </testsuite>\n';
}
xml+='</testsuites>\n';fs.writeFileSync(path.join(pub,'junit.xml'),xml);

// Summary.
const env=process.env;const runUrl=env.GITHUB_RUN_ID?`${env.GITHUB_SERVER_URL}/${env.GITHUB_REPOSITORY}/actions/runs/${env.GITHUB_RUN_ID}`:'';
let md=`# Ezano TV device check: ${status.toUpperCase()}\n\n`;
md+=`- Commit: ${env.GITHUB_SHA||'local'}${env.GITHUB_REF_NAME?` (${env.GITHUB_REF_NAME})`:''}\n- Run: ${runUrl||'local'}\n- Generated: ${new Date().toISOString()}\n- Tests: ${all.length}, failed/errored: ${failed.length}\n\n`;
if(steps.length===0){const errs=read(path.join(out,'build.log')).split('\n').filter(l=>/^e: |error:|FAILED|What went wrong/.test(l)).slice(0,30);md+='**No device steps ran.** The build or the emulator failed before tests started (see build-tail.txt and the workflow run).\n\n'+(errs.length?'```\n'+errs.join('\n')+'\n```\n\n':'')}
md+='## Device\n\n```\n'+read(path.join(out,'device.txt')).trim()+'\n```\n\n';
md+='## Results\n\n| Step | Test | Result | Step time |\n|---|---|---|---|\n';
for(const t of all)md+=`| ${t.step.name} | ${t.cls?.replace('tv.ezano.app.','')}#${t.name} | ${t.code==='0'?'PASS':(CODES[t.code]||t.code).toUpperCase()} | ${t.step.secs}s |\n`;
if(failed.length){md+='\n## Failures\n\n';for(const t of failed)md+=`### ${t.cls?.replace('tv.ezano.app.','')}#${t.name} (${t.step.name})\n\n\`\`\`\n${t.stack.split('\n').slice(0,18).join('\n')}\n\`\`\`\n\n`}
const fails=events.filter(e=>e.kind==='FAIL');
if(fails.length){md+='\n## Individual check failures (soft checks: the test kept going)\n\n';for(const e of fails)md+=`- [${e.step}] ${e.msg}\n`}
// Previous run's metrics (the summary.md the workflow fetched from the release before this run), for comparison.
const prevText=read(path.join(out,'previous-summary.md'));
const prev={};for(const l of prevText.split('\n')){const m=/^\| (\w+) \| ([^|]+?) \| [^|]+ \|/.exec(l);if(m&&/_ms$|_titles$|_programs$/.test(m[1]))prev[m[1]]=m[2].trim()}
const prevCommit=(/^- Commit: (\S+)/m.exec(prevText)||[])[1];
md+='\n## Startup and reopen times (ms)\n\n';
if(prevCommit)md+=`Previous run for comparison: ${prevCommit.slice(0,12)} (the device-check release before this run).\n\n`;
if(metrics.length){
  md+='| Metric | Value | Source | Previous run |\n|---|---|---|---|\n';for(const m of metrics){const [k,v]=m.text.split('=');md+=`| ${k} | ${v} | ${m.step} | ${prev[k]??''} |\n`}
  const stats=v=>v.length?`${Math.round(v.reduce((a,b)=>a+b,0)/v.length)} ms (n=${v.length}, min ${Math.min(...v)}, max ${Math.max(...v)})`:'n/a';
  const avg=re=>{const v=metrics.map(m=>m.text.split('=')).filter(([k,v])=>re.test(k)&&/^\d+$/.test(v)).map(([,v])=>+v);const p=Object.entries(prev).filter(([k,v])=>re.test(k)&&/^\d+$/.test(v)).map(([,v])=>+v);return stats(v)+(p.length?`; previous run ${stats(p)}`:'')};
  md+=`\n- Fixture cold start, process start to Home content: ${avg(/^fixture_cold\d+_process_to_home_ms$/)}\n- Fixture cold start, launch to Home content: ${avg(/^fixture_cold\d+_launch_to_home_ms$/)}\n- Fixture cold start, first frame (am start -W): ${avg(/^fixture_cold\d+_first_frame_ms$/)}\n- Host launcher cold start, first frame: ${avg(/^host_cold\d+_first_frame_ms$/)}\n- Warm reopen (HOME then relaunch) to Home content: ${avg(/^warm\d+_launch_to_home_ms$/)}\n- Large library (76,000 titles) cold start, process start to Home: ${avg(/^large_cold\d+_process_to_home_ms$/)}\n- Cold start with the update feed override, process start to Home: ${avg(/^updfeed_cold\d+_process_to_home_ms$/)}\n- Cold start without override (real update feed), process start to Home: ${avg(/^noupdate_cold\d+_process_to_home_ms$/)}\n- First D-pad response after cold start (all labels): ${avg(/_cold\d+_first_dpad_response_ms$/)}\n- D-pad response while the real update feed is checked: ${avg(/^update_none_dpad_response_avg_ms$/)}\n`;
}else md+='No metrics recorded.\n';
const routes=events.filter(e=>e.kind==='ROUTE');
md+='\n## How each user step was performed\n\n`dpad` = remote keys only; `touch-fallback` = the remote could not do it, so the test tapped; `direct` = activity/store API.\n\n';
for(const e of routes)md+=`- [${e.step}] ${e.msg}\n`;
const notes=events.filter(e=>e.kind==='NOTE');
if(notes.length){md+='\n## Observations (focus, state)\n\n';for(const e of notes)md+=`- [${e.step}] ${e.msg}\n`}
if(crashes.length){md+='\n## Crashes / ANRs in logcat\n\n';for(const c of crashes)md+=`[${c.step}]\n\`\`\`\n${c.text}\n\`\`\`\n`}
const base=env.GITHUB_REPOSITORY?`https://github.com/${env.GITHUB_REPOSITORY}/releases/download/device-check/`:'';
md+='\n## Screenshots\n\n'+shots.map(s=>`- ${base}${s}`).join('\n')+'\n';
md+='\nOther assets: junit.xml, events.txt (every D-pad press with the resulting focus), logcat-app.txt (app warnings/errors, crashes, test runner), instrument-output.txt (raw am instrument output), hierarchy-*.xml (window hierarchy at each failure).\n';
fs.writeFileSync(path.join(pub,'summary.md'),md);
fs.writeFileSync(path.join(pub,'events.txt'),events.map(e=>`[${e.step}] ${e.kind} ${e.msg}`).join('\n')+'\n');
fs.writeFileSync(path.join(pub,'logcat-app.txt'),logcatApp);
fs.writeFileSync(path.join(pub,'instrument-output.txt'),rawAll+'\n\n===== host.log\n'+read(path.join(out,'host.log'))+'\n===== install.txt\n'+read(path.join(out,'install.txt')));
console.log(md);
