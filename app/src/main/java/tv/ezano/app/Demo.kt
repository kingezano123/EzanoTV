package tv.ezano.app

object Demo {
    val movies=listOf(
        Channel("demo:bunny","demo","1","Big Buck Bunny","https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",Kind.MOVIE,"Animation","file:///android_asset/bunny.jpg",description="A gentle giant finds his day interrupted by a trio of mischievous forest creatures. An open animated short from the Blender Foundation.",year="2008"),
        Channel("demo:sintel","demo","2","Sintel","https://download.blender.org/durian/movies/Sintel.2010.1080p.mkv",Kind.MOVIE,"Adventure","file:///android_asset/sintel.jpg",description="A young traveler sets out to find a dragon she once befriended. An open film from the Blender Foundation.",year="2010"),
        Channel("demo:steel","demo","3","Tears of Steel","https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov",Kind.MOVIE,"Science fiction","file:///android_asset/steel.jpg",description="A group of scientists and warriors attempts to rescue the world from destructive robots. An open Blender Foundation film.",year="2012"),
        Channel("demo:trailer","demo","4","Sintel · The trailer","https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4",Kind.MOVIE,"Behind the film","file:///android_asset/trailer.jpg",description="A first look at the world of Sintel. An open film trailer from the Blender Foundation.",year="2010")
    )
}
