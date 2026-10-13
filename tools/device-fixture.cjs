// Device-check provider: a small synthetic Xtream service whose titles match the curated
// kids & family and popular lists, so Home, Movies and Shows render their real hero and rows.
// Loopback only (the emulator reaches it at 10.0.2.2:8769). Never contains real IPTV credentials.
// Video is the Sintel open-film trailer (CC BY 3.0, Blender Foundation) prepared per TESTING.md.
const http=require('http'),fs=require('fs'),path=require('path');
const PORT=+(process.env.DEVICE_FIXTURE_PORT||8769);
const media=path.join(__dirname,'../test-media');
const assets=path.resolve(__dirname,'../app/src/main/assets');
const base=`http://10.0.2.2:${PORT}`;
const art=['sintel','steel','bunny','trailer'];
const kids={language:'en',country:'US',genre:'Animation, Family'};
const movies=[
 // Recent kids & family hits (curated "Popular kids movies"), then classics, then popular titles.
 ['EN - Inside Out 2 (2024)',2024,'PG',kids],['EN - Moana 2 (2024)',2024,'PG',kids],['EN - The Wild Robot (2024)',2024,'PG',kids],
 ['EN - Zootopia 2 (2025)',2025,'PG',kids],['EN - Lilo & Stitch (2025)',2025,'PG',{...kids,genre:'Family, Comedy'}],
 ['EN - Toy Story (1995)',1995,'G',kids],['EN - Finding Nemo (2003)',2003,'G',kids],['EN - Frozen (2013)',2013,'PG',kids],
 ['EN - Shrek (2001)',2001,'PG',kids],['EN - Up (2009)',2009,'PG',kids],['EN - Cars (2006)',2006,'G',kids],
 ['EN - Dune: Part Two (2024)',2024,'PG-13',{language:'en',country:'US',genre:'Science Fiction, Adventure'}],
 ['EN - Top Gun: Maverick (2022)',2022,'PG-13',{language:'en',country:'US',genre:'Action, Drama'}],
 ['EN - Wicked (2024)',2024,'PG',{language:'en',country:'US',genre:'Fantasy, Music'}],
].map(([name,year,certification,meta],i)=>({stream_id:101+i,name,year:String(year),certification,...meta,rating:(8.6-i*0.1).toFixed(1),category_id:certification==='PG-13'||name.includes('Wicked')?'11':'10',stream_icon:`${base}/art/${art[i%4]}.jpg`,container_extension:'mp4',plot:`Device-check fixture title ${i+1}. Video is the Sintel open-film trailer.`}));
const series=[['Bluey',2018,'TV-Y'],['Peppa Pig',2004,'TV-Y'],['PAW Patrol',2013,'TV-Y'],['SpongeBob SquarePants',1999,'TV-Y7'],['Wednesday',2022,'TV-14']]
 .map(([name,year,certification],i)=>({series_id:201+i,name:`EN - ${name}`,year:String(year),certification,language:'en',country:'US',genre:certification==='TV-14'?'Comedy, Fantasy':'Animation, Kids',rating:(8.4-i*0.1).toFixed(1),category_id:certification==='TV-14'?'21':'20',cover:`${base}/art/${art[(i+1)%4]}.jpg`,plot:'Device-check fixture series.'}));
// 14 channels so the guide scrolls (LiveBackTest checks Back lands on a row below the fold); 2 are in "UK | Kids".
const liveKids=new Set(['Kids Channel','Kids Cartoons']);
const live=['UK Channel 1','UK Channel 2','Kids Channel','Film Channel','News Channel','Sports Channel','Music Channel','Documentary Channel','Comedy Channel','UK Channel 3','Kids Cartoons','Science Channel','History Channel','Nature Channel']
 .map((name,i)=>({stream_id:301+i,name,category_id:liveKids.has(name)?'31':'30',stream_icon:`${base}/art/${art[i%4]}.jpg`,epg_channel_id:`dc${i+1}`,tv_archive:0}));
const cats={live:[{category_id:'30',category_name:'UK | Entertainment'},{category_id:'31',category_name:'UK | Kids'}],vod:[{category_id:'10',category_name:'EN | Kids & Family'},{category_id:'11',category_name:'EN | Movies'}],series:[{category_id:'20',category_name:'EN | Kids Shows'},{category_id:'21',category_name:'EN | TV Shows'}]};
const titles=['Morning stories','Around the world','Family favorites','Evening edition','Stories worth sharing','Animation showcase'];
const updateApk=(()=>{const data=Buffer.from('Ezano device-check placeholder, not an APK\n'.repeat(64));return {data,sha256:require('crypto').createHash('sha256').update(data).digest('hex')}})();
const stamp=ms=>new Date(ms).toISOString().replace(/[-:T]/g,'').slice(0,14)+' +0000';
const server=http.createServer((req,res)=>{
 const u=new URL(req.url,base);const json=x=>{res.setHeader('content-type','application/json');res.end(JSON.stringify(x))};
 if(u.pathname==='/health'){res.end('ok');return}
 // In-app updater feed for UpdatePromptTest (debug builds read the override from SharedPreferences "updates"/"feed").
 // The APK is a placeholder: the test never presses "Update now", and a real download would fail the sha256 check.
 if(u.pathname==='/update.json'){json({versionCode:999,versionName:'9.9.9',apk:`${base}/update.apk`,sha256:updateApk.sha256,size:updateApk.data.length,notes:'Test update'});return}
 if(u.pathname==='/update.apk'){res.writeHead(200,{'content-type':'application/vnd.android.package-archive','content-length':updateApk.data.length});res.end(updateApk.data);return}
 if(u.pathname==='/captions-en.vtt'){res.setHeader('content-type','text/vtt');res.end('WEBVTT\n\n00:00.000 --> 10:00.000\nDevice check English captions\n');return}
 if(u.pathname==='/player_api.php'){
  if(u.searchParams.get('username')!=='test'||u.searchParams.get('password')!=='test'){json({user_info:{auth:0}});return}
  const action=u.searchParams.get('action');
  switch(action){
   case null:json({user_info:{auth:1,status:'Active'}});break;
   case 'get_live_categories':json(cats.live);break;
   case 'get_vod_categories':json(cats.vod);break;
   case 'get_series_categories':json(cats.series);break;
   case 'get_live_streams':json(live);break;
   case 'get_vod_streams':json(movies);break;
   case 'get_series':json(series);break;
   case 'get_series_info':{const id=+u.searchParams.get('series_id');json({episodes:{'1':[1,2,3].map(n=>({id:String(id*10+n),episode_num:n,title:`Episode ${n}`,container_extension:'mp4',info:{plot:'Fixture episode using the Sintel trailer.',movie_image:`${base}/art/${art[n%4]}.jpg`}}))}});break}
   case 'get_vod_info':{const m=movies.find(x=>String(x.stream_id)===u.searchParams.get('vod_id'))||movies[0];json({subtitles:[{url:`${base}/captions-en.vtt`,language:'en',label:'English'}],info:{plot:m.plot,backdrop_path:[`${base}/art/${art[(m.stream_id+1)%4]}.jpg`],releasedate:`${m.year}-06-01`,rating:m.rating,certification:m.certification,genre:m.genre,original_language:'en',origin_country:'US'}});break}
   default:json([]);
  }
  return;
 }
 if(u.pathname==='/xmltv.php'){
  const hour=Math.floor(Date.now()/3600000)*3600000;
  res.setHeader('content-type','application/xml');
  res.end('<tv>'+live.flatMap((c,ci)=>[-2,-1,0,1,2,3,4,5].map(i=>`<programme channel="${c.epg_channel_id}" start="${stamp(hour+i*3600000)}" stop="${stamp(hour+(i+1)*3600000)}"><title>${titles[(i+8+ci)%6]}</title><desc>Device-check guide entry for ${c.name}.</desc></programme>`)).join('')+'</tv>');
  return;
 }
 if(u.pathname.startsWith('/live/')){
  const file=path.join(media,'fixture.ts');if(!fs.existsSync(file)){res.writeHead(404);res.end();return}
  const data=fs.readFileSync(file);res.writeHead(200,{'content-type':'video/mp2t'});let pos=0;
  const timer=setInterval(()=>{if(res.destroyed){clearInterval(timer);return}const next=Math.min(pos+188*180,data.length);res.write(data.subarray(pos,next));pos=next>=data.length?0:next},150);
  req.on('close',()=>clearInterval(timer));return;
 }
 const file=u.pathname.startsWith('/art/')?path.join(assets,path.basename(u.pathname)):path.join(media,'sintel-trailer.mp4');
 if(!fs.existsSync(file)){res.writeHead(404);res.end();return}
 const size=fs.statSync(file).size;let start=0,end=size-1,status=200;
 if(req.headers.range){const m=/bytes=(\d+)-(\d*)/.exec(req.headers.range);if(m){start=+m[1];end=m[2]?+m[2]:end;status=206}}
 res.setHeader('accept-ranges','bytes');res.setHeader('content-type',file.endsWith('.jpg')?'image/jpeg':'video/mp4');res.setHeader('content-length',end-start+1);
 if(status===206)res.setHeader('content-range',`bytes ${start}-${end}/${size}`);
 res.writeHead(status);fs.createReadStream(file,{start,end}).pipe(res);
});
server.listen(PORT,'127.0.0.1',()=>console.log(`Device-check fixture on ${PORT}`));
