const http=require('http'),fs=require('fs'),path=require('path');
const media=path.join(__dirname,'../test-media');const assets=path.resolve(__dirname,'../app/src/main/assets');
const base='http://10.0.2.2:8765';
const categories=[{category_id:'1',category_name:'Open films'},{category_id:'2',category_name:'Cinema'}];
const server=http.createServer((req,res)=>{const u=new URL(req.url,base);const json=x=>{res.setHeader('content-type','application/json');res.end(JSON.stringify(x))};
if(u.pathname==='/captions-en.vtt'){res.setHeader('content-type','text/vtt');res.end('WEBVTT\n\n00:00.000 --> 01:00.000\nAUTOMATIC English captions\n');return;}
if(u.pathname==='/missing.xml'){res.writeHead(404);res.end();return;}
if(u.pathname==='/player_api.php'){
 if(u.searchParams.get('username')!=='test' || u.searchParams.get('password')!=='test'){json({user_info:{auth:0}});return}
 switch(u.searchParams.get('action')){
 case null:json({user_info:{auth:1,status:'Active'}});break;
 case 'get_live_categories':case 'get_vod_categories':case 'get_series_categories':json(categories);break;
 case 'get_live_streams':json([1,2,3,4].map((id)=>({stream_id:id,name:['Sintel Live','Open Cinema','Adventure Channel','Film Studio'][id-1],category_id:'1',stream_icon:`${base}/art/${['sintel','steel','bunny','trailer'][id-1]}.jpg`,epg_channel_id:'c'+id,tv_archive:1,tv_archive_duration:3})));break;
 case 'get_vod_streams':json([1,2,3,4,5,6].map(id=>({stream_id:id,name:['Sintel','Tears of Steel','Big Buck Bunny','Sintel · The trailer','Open film collection','Behind the frame'][id-1],category_id:id%2?'1':'2',stream_icon:`${base}/art/${['sintel','steel','bunny','trailer'][((id-1)%4)]}.jpg`,container_extension:'mp4',year:2010})));break;
 case 'get_series':json([{series_id:1,name:'Open Film Stories',category_id:'1',cover:`${base}/art/steel.jpg`,plot:'Controlled test series.'}]);break;
 case 'get_series_info':json({episodes:{'1':[{id:1,episode_num:1,title:'The making of a world',container_extension:'mp4',info:{plot:'A test episode using the Sintel trailer',movie_image:`${base}/art/sintel.jpg`}}]}});break;
 case 'get_vod_info':json({subtitles:u.searchParams.get('vod_id')==='21'?[{url:`${base}/captions-en.vtt`,language:'en',label:'English'}]:[],info:{plot:'An open-film test title for validating your Ezano TV experience.',backdrop_path:[`${base}/art/steel.jpg`],releasedate:'2010-01-01',rating:'8.2'}});break;
 default:json([]);
 }return;
}
if(u.pathname==='/xmltv.php'){
 const stamp=ms=>new Date(ms).toISOString().replace(/[-:T]/g,'').slice(0,14)+' +0000'; const hour=Math.floor(Date.now()/3600000)*3600000;
 res.setHeader('content-type','application/xml');res.end('<tv>'+[1,2,3,4].flatMap(c=>[-2,-1,0,1,2,3,4,5].map((i)=>`<programme channel="c${c}" start="${stamp(hour+i*3600000)}" stop="${stamp(hour+(i+1)*3600000)}"><title>${['Open Film Premiere','Behind the scenes','Sintel: A world of adventure','The evening collection','Stories worth sharing','Animation showcase'][(i+8+c)%6]}</title><desc>Controlled EPG fixture. A full program description for validating the live guide, reminders and recording workflow.</desc></programme>`)).join('')+'</tv>');return;
}
if(u.pathname.startsWith('/live/')) {const file=fs.readFileSync(path.join(media,'fixture.ts'));res.writeHead(200,{'content-type':'video/mp2t'});let pos=0;const timer=setInterval(()=>{if(res.destroyed){clearInterval(timer);return}const next=Math.min(pos+188*180,file.length);res.write(file.subarray(pos,next));pos=next>=file.length?0:next},150);req.on('close',()=>clearInterval(timer));return}
let file=u.pathname.startsWith('/art/')?path.join(assets,path.basename(u.pathname)):path.join(media,'sintel-trailer.mp4');if(!fs.existsSync(file)){res.writeHead(404);res.end();return}
const size=fs.statSync(file).size;let start=0,end=size-1;let status=200;if(req.headers.range){const m=/bytes=(\d+)-(\d*)/.exec(req.headers.range);if(m){start=+m[1];end=m[2]?+m[2]:end;status=206}}res.setHeader('accept-ranges','bytes');res.setHeader('content-type',file.endsWith('.jpg')?'image/jpeg':file.endsWith('.png')?'image/png':'video/mp4');res.setHeader('content-length',end-start+1);if(status===206)res.setHeader('content-range',`bytes ${start}-${end}/${size}`);res.writeHead(status);fs.createReadStream(file,{start,end}).pipe(res);
});server.listen(8765,'127.0.0.1',()=>console.log('Fixture server on 8765'));
