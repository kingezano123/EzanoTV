package tv.ezano.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

val Ink=Color.Black
val Panel=Color(0xFF181818)
val Muted=Color(0xFFAAAAAA)
val Lime=Color(0xFFE50914)
val LocalAccent=staticCompositionLocalOf { Lime }
val LocalMotion=staticCompositionLocalOf { true }

@Composable fun EzButton(label: String,icon: ImageVector?=null,primary: Boolean=false,selected: Boolean=false,modifier: Modifier=Modifier,onFocus: (() -> Unit)?=null,onClick: ()->Unit) {
    var focused by remember { mutableStateOf(false) }; val accent=LocalAccent.current; val motion=LocalMotion.current
    val scale by animateFloatAsState(if(focused && motion) 1.06f else 1f,label="button")
    // Focus must be unmistakable from across the room: only the focused button is solid white.
    // Primary actions stay emphasized with a bright translucent fill and a white outline.
    val fill=when { focused -> Color.White; primary -> Color.White.copy(.24f); selected -> Color(0xFF333333); else -> Panel.copy(.85f) }
    val stroke=when { focused -> accent; primary -> Color.White.copy(.85f); selected -> Color.White.copy(.5f); else -> Color.Transparent }
    Row(modifier.heightIn(min=36.dp).graphicsLayer { scaleX=scale; scaleY=scale }.onFocusChanged { focused=it.isFocused; if(it.isFocused) onFocus?.invoke() }.clip(RoundedCornerShape(4.dp)).background(fill).border(if(focused) 3.dp else 1.5.dp,stroke,RoundedCornerShape(4.dp)).semantics { this.selected=selected }.clickable(role=Role.Button,onClick=onClick).padding(horizontal=15.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        val color=if(focused) Ink else Color.White
        if(icon!=null) Icon(icon,null,Modifier.size(17.dp),tint=color)
        Text(label,color=color,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
@Composable fun Tag(text: String,color: Color=LocalAccent.current) { Text(text,color=color,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp,modifier=Modifier.clip(RoundedCornerShape(50)).background(color.copy(.13f)).padding(horizontal=7.dp,vertical=4.dp)) }
@Composable fun Heading(title: String,subtitle: String="") { Column(Modifier.padding(bottom=16.dp)) { Text(title,color=Color.White,style=MaterialTheme.typography.headlineLarge,fontSize=28.sp,fontWeight=FontWeight.ExtraBold); if(subtitle.isNotBlank()) Text(subtitle,color=Muted,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=6.dp)) } }
/** D-pad arrow → focus direction, for text fields that are not being edited. */
internal fun remoteDirection(keyCode: Int): FocusDirection? = when(keyCode) {
    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
    android.view.KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
    else -> null
}
internal fun hasArt(url: String)=url.isNotBlank() && url!="null"
@Composable fun Art(model: Any?,modifier: Modifier=Modifier,alignment: Alignment=Alignment.Center) { Box(modifier.background(Panel)) { if(model!=null && model!="" && model!="null" && model!="file:///android_asset/hero.png") AsyncImage(model,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop,alignment=alignment) } }
@Composable fun Poster(c: Channel,sub: String="",favorite: Boolean=false,portrait: Boolean=false,onFocus: ()->Unit={},onClick: ()->Unit) {
    var focused by remember { mutableStateOf(false) }; val scale by animateFloatAsState(if(focused && LocalMotion.current) 1.035f else 1f,label="poster")
    Column(Modifier.width(if(portrait) 118.dp else 205.dp).graphicsLayer { scaleX=scale; scaleY=scale }.onFocusChanged { focused=it.isFocused; if(it.isFocused) onFocus() }.clip(RoundedCornerShape(5.dp)).background(Color.Transparent).border(if(focused) 2.dp else 0.dp,if(focused) Color.White else Color.Transparent,RoundedCornerShape(5.dp)).clickable(role=Role.Button,onClick=onClick).padding(3.dp)) {
        Box(Modifier.fillMaxWidth().height(if(portrait) 168.dp else 110.dp).clip(RoundedCornerShape(4.dp))) {
            Art(c.logo.takeIf(::hasArt) ?: c.backdrop,Modifier.fillMaxSize())
            if(!hasArt(c.logo) && !hasArt(c.backdrop)) Text(if(c.kind==Kind.SERIES) "SERIES" else if(c.kind==Kind.LIVE) "LIVE TV" else "EZANO CINEMA",color=Muted.copy(.6f),fontSize=10.sp,letterSpacing=2.sp,modifier=Modifier.align(Alignment.Center))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Transparent,Ink.copy(.85f)))))
            if(c.kind==Kind.LIVE) Box(Modifier.padding(8.dp).align(Alignment.BottomStart)) { Tag("● LIVE",Color(0xFFFF6D7A)) }
            if(favorite) Text("★",color=LocalAccent.current,modifier=Modifier.align(Alignment.TopEnd).padding(9.dp),fontSize=15.sp)
        }
        // Movie and series names are often much longer than channel names. Reserve two
        // readable lines so they don't disappear behind a one-line ellipsis or jump in height.
        Text(HomeRecommendations.title(if(c.kind==Kind.EPISODE) c.copy(name=c.group) else c),color=Color.White,fontSize=11.5.sp,lineHeight=14.sp,fontWeight=FontWeight.SemiBold,minLines=1,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().padding(start=3.dp,top=7.dp,end=3.dp))
        Text(sub.ifBlank {if(c.kind==Kind.EPISODE) c.name.substringBefore("  ") else c.year.take(4)},color=Muted,fontSize=8.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(start=3.dp,top=3.dp,bottom=5.dp,end=3.dp))
    }
}
@Composable fun Modal(title: String,onDismiss: ()->Unit,content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(color=Panel,shape=RoundedCornerShape(7.dp),border=BorderStroke(1.dp,Color.White.copy(.1f)),modifier=Modifier.width(640.dp).heightIn(max=480.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(26.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(title,color=Color.White,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f)); EzButton("Close",onClick=onDismiss) }
                content()
            }
        }
    }
}
/** Remote-friendly text field: moving onto it never opens the keyboard (which would trap the D-pad);
 * Select opens it, Back or Next closes it and Next moves on to the following field or button.
 */
@Composable fun Field(label: String,value: String,onChange: (String)->Unit,secret: Boolean=false) {
    val focusManager=androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val inputMode=androidx.compose.ui.platform.LocalInputModeManager.current
    var editing by remember {mutableStateOf(false)}
    var pendingSelectKey by remember {mutableIntStateOf(0)}
    val interactions=remember {androidx.compose.foundation.interaction.MutableInteractionSource()}
    val imeOpen=androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
    var imeWasOpen by remember {mutableStateOf(false)}
    LaunchedEffect(imeOpen) {if(imeOpen) imeWasOpen=true else if(imeWasOpen) {imeWasOpen=false;editing=false;pendingSelectKey=0}}
    LaunchedEffect(editing) {if(editing) {kotlinx.coroutines.delay(80);inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard);keyboard?.show()} else keyboard?.hide()}
    LaunchedEffect(interactions) {interactions.interactions.collect {if(it is androidx.compose.foundation.interaction.PressInteraction.Release) editing=true}}
    androidx.activity.compose.BackHandler(enabled=editing) {editing=false;pendingSelectKey=0;keyboard?.hide()}
    fun next() {editing=false;pendingSelectKey=0;keyboard?.hide();focusManager.moveFocus(FocusDirection.Down)}
    OutlinedTextField(value,onChange,label={ Text(label,style=MaterialTheme.typography.bodyMedium) },singleLine=true,readOnly=!editing,interactionSource=interactions,
        modifier=Modifier.fillMaxWidth().onFocusChanged {if(!it.isFocused) {editing=false;pendingSelectKey=0} else if(!editing) keyboard?.hide()}
            .onPreviewKeyEvent {event->
                val native=event.nativeKeyEvent
                val select=native.keyCode==android.view.KeyEvent.KEYCODE_DPAD_CENTER || native.keyCode==android.view.KeyEvent.KEYCODE_ENTER
                // Not typing: arrows move between controls instead of being taken as cursor keys by the text field.
                val direction=remoteDirection(native.keyCode)
                if(!editing && direction!=null) {if(native.action==android.view.KeyEvent.ACTION_DOWN) focusManager.moveFocus(direction);true}
                else if(!select || editing) false else when(native.action) {
                    android.view.KeyEvent.ACTION_DOWN -> {if(pendingSelectKey==0) pendingSelectKey=native.keyCode;true}
                    android.view.KeyEvent.ACTION_UP -> {val pending=pendingSelectKey==native.keyCode;pendingSelectKey=0;if(pending && !native.isCanceled) editing=true;pending}
                    else -> pendingSelectKey==native.keyCode
                }
            },
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Next,showKeyboardOnFocus=false),
        keyboardActions=androidx.compose.foundation.text.KeyboardActions(onNext={next()},onDone={next()}),
        shape=RoundedCornerShape(14.dp),textStyle=MaterialTheme.typography.bodyLarge,visualTransformation=if(secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=LocalAccent.current,unfocusedBorderColor=Muted.copy(.4f),focusedContainerColor=Color.White.copy(.04f),unfocusedContainerColor=Ink.copy(.2f),focusedLabelColor=LocalAccent.current,unfocusedLabelColor=Muted,focusedTextColor=Color.White,unfocusedTextColor=Color.White,cursorColor=LocalAccent.current))
}
@Composable fun SettingRow(title: String,detail: String,value: String,onClick: ()->Unit) { Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) { Column(Modifier.weight(1f)) { Text(title,color=Color.White,style=MaterialTheme.typography.titleSmall); Text(detail,color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=4.dp)) }; EzButton(value,onClick=onClick) } }
