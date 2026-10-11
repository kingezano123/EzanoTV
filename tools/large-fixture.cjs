// Local-only synthetic provider. Never contains real IPTV credentials.
const http=require('http'),zlib=require('zlib'),fs=require('fs'),path=require('path');
const localMedia=path.resolve(__dirname,'../test-media/fixture.ts');
const media=process.env.FIXTURE_MEDIA_PATH||(fs.existsSync(localMedia)?localMedia:path.resolve(__dirname,'../../../work/media/fixture.ts'));
const stamp=n=>new Date(n).toISOString().replace(/[-:T]/g,'').slice(0,14)+' +0000';
http.createServer(async(req,res)=>{
 const u=new URL(req.url,'http://localhost:8766');
 const write=async text=>{if(!res.write(text))await new Promise(resolve=>res.once('drain',resolve))};
 const send=x=>res.end(JSON.stringify(x));
 res.setHeader('Content-Type','application/json');
 if(u.pathname==='/player_api.php') {
  const action=u.searchParams.get('action');
  if(!action)return send({user_info:{auth:'1',status:'Active'}});
  if(action.endsWith('_categories'))return send([{category_id:1,category_name:'Test collection'}]);
  const kind=action==='get_live_streams'?'live':action==='get_vod_streams'?'movie':'series';
  const count=kind==='live'?2000:kind==='movie'?68000:6000;
  await write('[');
  for(let i=1;i<=count;i++) {
   const row={stream_id:i,series_id:i,name:`${kind} ${i}`,category_id:1,epg_channel_id:kind==='live'?`channel.${i}`:null,plot:'Synthetic large provider entry. '.repeat(10),stream_icon:null,container_extension:kind==='live'?'ts':'mp4'};
   await write((i===1?'':',')+JSON.stringify(row));
   if(i===1 || i%100===0)await write(','+JSON.stringify(row));
  }
  res.end(']');return;
 }
 if(u.pathname.startsWith('/live/')) {
  if(!fs.existsSync(media)){res.writeHead(404);res.end();return}
  const file=fs.readFileSync(media);res.writeHead(200,{'content-type':'video/mp2t'});let pos=0;
  const timer=setInterval(()=>{if(res.destroyed){clearInterval(timer);return}const next=Math.min(pos+188*180,file.length);res.write(file.subarray(pos,next));pos=next>=file.length?0:next},150);
  req.on('close',()=>clearInterval(timer));return
 }
 if(u.pathname==='/xmltv.php') {
  res.setHeader('Content-Type','application/gzip');const gzip=zlib.createGzip();gzip.pipe(res);
  const put=async text=>{if(!gzip.write(text))await new Promise(resolve=>gzip.once('drain',resolve))};
  const now=Math.floor(Date.now()/3600000)*3600000;
  await put('<tv>');
  for(let c=1;c<=2000;c++)for(let i=-4;i<28;i++) {
   const row=`<programme channel="channel.${c}" start="${stamp(now+i*3600000)}" stop="${stamp(now+(i+1)*3600000)}"><title>Program ${c} / ${i}</title><desc>${'Large guide description. '.repeat(10)}</desc></programme>`;
   await put(row);if(i===0)await put(row);
  }
  gzip.end('</tv>');return;
 }
 res.writeHead(404);res.end();
}).listen(8766,'127.0.0.1',()=>console.log('Large synthetic provider ready on 8766'));
