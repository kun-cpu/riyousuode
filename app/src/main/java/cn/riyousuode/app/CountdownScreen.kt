package cn.riyousuode.app

import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.ZoneId

@Composable fun rememberCalendarDay(): LocalDate {
    var day by remember { mutableStateOf(LocalDate.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while(true) { day = LocalDate.now(); delay(1000) } } }
    return day
}

fun LazyListScope.countdownPage(state: AppState, day: LocalDate, add: () -> Unit, edit: (Countdown) -> Unit, delete: (Countdown) -> Unit) {
    item { Text("把期待留给每一个重要的日子。", color = Color(0xFF69766C), fontSize = 14.sp) }
    if(state.countdowns.isEmpty()) item { Empty("下一个期待，是什么？", "写下一件事和日期，选一张喜欢的背景。", "添加倒计时", add) }
    items(Countdowns.sorted(state.countdowns, day), key = { it.id }) { c ->
        CountdownCard(c, day) {
            Row {
                TextButton(onClick = { edit(c) }) { Text("编辑", color = Color.White) }
                TextButton(onClick = { delete(c) }) { Text("删除", color = Color.White) }
            }
        }
    }
}

@Composable fun Landscape(kind: String, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sunset = kind == "sunset"; val mountain = kind == "mountain"
        drawRect(Brush.verticalGradient(if(sunset) listOf(Color(0xFFE7A278), Color(0xFF745C78)) else listOf(Color(0xFF8EC7D3), Color(0xFFDDE8DD))))
        drawCircle(if(sunset) Color(0xFFFFD6A0) else Color(0xFFF3F0DA), w*.09f, Offset(w*.76f,h*.22f))
        val back = Path().apply { moveTo(0f,h*.6f); lineTo(w*.16f,h*.35f); lineTo(w*.29f,h*.52f); lineTo(w*.52f,h*.18f); lineTo(w*.79f,h*.55f); lineTo(w,h*.37f); lineTo(w,h); lineTo(0f,h); close() }
        drawPath(back, if(sunset) Color(0xFF836E80) else Color(0xFF6C9394))
        val front = Path().apply { moveTo(0f,h*.73f); lineTo(w*.2f,h*.57f); lineTo(w*.42f,h*.77f); lineTo(w*.7f,h*.45f); lineTo(w,h*.68f); lineTo(w,h); lineTo(0f,h); close() }
        drawPath(front, if(sunset) Color(0xFF454D60) else Color(0xFF3C6B68))
        if(!mountain) {
            drawRect(Brush.verticalGradient(listOf(if(sunset) Color(0xFFB99087) else Color(0xFF79AFB3), Color(0xFF2D646A)), startY = h*.76f), Offset(0f,h*.76f), androidx.compose.ui.geometry.Size(w,h*.24f))
            repeat(7) { i -> val y=h*(.79f+i*.026f); drawLine(Color.White.copy(alpha=.15f), Offset(w*(.53f-i*.02f),y),Offset(w*(.86f+i*.01f),y),2f) }
        }
    }
}

@Composable fun CountdownCard(c: Countdown, day: LocalDate, preview: ByteArray? = null, actions: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, c.photo, preview) {
        value = withContext(Dispatchers.IO) { runCatching {
            if(preview != null) BitmapFactory.decodeByteArray(preview,0,preview.size)?.asImageBitmap()
            else c.photo?.let { BitmapFactory.decodeFile(Pictures.directory(context).resolve(it).path)?.asImageBitmap() }
        }.getOrNull() }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
        Landscape(c.background, Modifier.matchParentSize())
        bitmap?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.3f),Color.Black.copy(alpha=.65f)))))
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(c.title.ifBlank { "想见的风景，想做的事" }, color=Color.White, fontSize=20.sp, fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            Text(Countdowns.label(c,day), color=Color.White, fontSize=34.sp, fontWeight=FontWeight.Bold)
            Text("${c.date.replace('-', '.')}  ·  ${if(Countdowns.days(c,day)<0) "值得记住" else "值得期待"}", color=Color.White.copy(alpha=.9f), fontSize=13.sp)
            actions()
        }
    }
}

@Composable fun CountdownForm(old: Countdown?, busy: Boolean, dismiss: () -> Unit, save: (Countdown, ByteArray?) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current; val keyboard = LocalSoftwareKeyboardController.current
    val id = remember { old?.id ?: uid() }
    var title by remember { mutableStateOf(old?.title ?: "") }
    var date by remember { mutableStateOf(old?.date ?: today()) }
    var background by remember { mutableStateOf(old?.background ?: "lake") }
    var photo by remember { mutableStateOf(old?.photo) }
    var image by remember { mutableStateOf<ByteArray?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val day = rememberCalendarDay()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri != null) scope.launch {
            loading = true
            try { image = withContext(Dispatchers.IO) { Pictures.import(context, uri) }; photo = null; error = "" }
            catch(e: Exception) { error = "图片未更换：${e.message}" }
            finally { loading = false }
        }
    }
    Form(if(old == null) "添加倒计时" else "编辑倒计时", busy || loading, dismiss, {
        try { val c = Countdown(id,title.trim(),date,background,photo); Countdowns.validate(c); save(c,image) }
        catch(e: Exception) { error = e.message ?: "请检查输入" }
    }) {
        Field("事情名称", title, { title=it })
        OutlinedButton(onClick = {
            focus.clearFocus(); keyboard?.hide()
            val selected = LocalDate.parse(date)
            DatePickerDialog(context, { _, year, month, d -> date = LocalDate.of(year,month+1,d).toString() },selected.year,selected.monthValue-1,selected.dayOfMonth).apply {
                datePicker.minDate = LocalDate.of(2000,1,1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                datePicker.maxDate = LocalDate.of(2100,12,31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                setButton(android.content.DialogInterface.BUTTON_POSITIVE, "确定") { _, _ -> date = LocalDate.of(datePicker.year, datePicker.month+1, datePicker.dayOfMonth).toString() }
                setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "取消") { _, _ -> }
            }.show()
        }) { Text("目标日期：$date") }
        Text("默认背景")
        Choices(Countdowns.backgrounds, if(photo != null || image != null) "" else background) { background=it; photo=null; image=null }
        TextButton(enabled=!loading, onClick={ picker.launch("image/*") }) { Text(if(loading) "正在处理图片…" else "从相册选择背景") }
        if(photo != null || image != null) TextButton(onClick={ photo=null; image=null; background="lake" }) { Text("恢复默认背景") }
        CountdownCard(Countdown(id,title,date,background,photo),day,image)
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }
}
