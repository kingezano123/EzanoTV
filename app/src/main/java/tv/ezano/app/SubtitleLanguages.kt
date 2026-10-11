package tv.ezano.app

object SubtitleLanguages {
    fun matches(language:String?,preferred:String="en"):Boolean {
        fun canonical(value:String)=value.lowercase().replace('_','-').substringBefore('-').let {when(it){"eng","english"->"en";else->it}}
        return language!=null && canonical(language)==canonical(preferred)
    }
}
