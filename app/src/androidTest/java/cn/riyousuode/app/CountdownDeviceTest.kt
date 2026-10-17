package cn.riyousuode.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class CountdownDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as DayApplication
    @Before fun reset() { runBlocking { app.store.restore(Backup.encode(AppState())) }; rule.waitForIdle() }
    private fun picture(): ByteArray {
        val bitmap=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.rgb(60,130,150))
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it);bitmap.recycle();it.toByteArray() }
    }
    private fun shot(name:String) {
        rule.waitForIdle()
        Thread.sleep(350)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        app.getExternalFilesDir(null)!!.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun countdownUiCreateCalendarEditAndDelete() {
        rule.onNodeWithText("倒计时").performClick();shot("07-countdown-empty")
        rule.onNodeWithContentDescription("新增倒计时").performClick()
        rule.onNodeWithText("事情名称").performTextInput("期待的旅行")
        rule.onNodeWithText("目标日期：${today()}").performScrollTo().performClick()
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        rule.waitUntil(5000) { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("确定")?.isNotEmpty()==true }
        automation.rootInActiveWindow.findAccessibilityNodeInfosByText("确定").first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        rule.waitUntil(5000) { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("确定")?.isEmpty()==true }
        rule.onNodeWithText("山川").performScrollTo().performClick()
        shot("08-countdown-form")
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(10000) { app.store.state.value!!.countdowns.size==1 }
        rule.onNodeWithText("就是今天").assertExists()
        Assert.assertEquals("mountain",app.store.state.value!!.countdowns.single().background)
        rule.onNodeWithText("编辑").performScrollTo().performClick()
        rule.onNodeWithText("事情名称").performTextReplacement("修改后的旅行")
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(10000) { app.store.state.value!!.countdowns.single().title=="修改后的旅行" }
        rule.onNodeWithText("删除").performScrollTo().performClick();rule.onNodeWithText("确认").performClick()
        rule.waitUntil(10000) { app.store.state.value!!.countdowns.isEmpty() }
        Assert.assertEquals(0L,app.store.state.value!!.balance)
    }
    @Test fun rotatedPhotoIsBoundedCopiedAndSurvivesOriginalRemoval() {
        val source=app.cacheDir.resolve("orientation.jpg")
        val bitmap=Bitmap.createBitmap(2400,1200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.CYAN)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
        ExifInterface(source.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes() }
        Assert.assertEquals(6,Pictures.jpegOrientation(source.readBytes()))
        val bytes=Pictures.import(app,Uri.fromFile(source));Pictures.validate(bytes)
        val result=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        Assert.assertTrue(result.height>result.width);Assert.assertTrue(result.height<=1280);result.recycle()
        runBlocking { app.store.saveCountdown(Countdown(title="自己的照片",date=today()),bytes) }
        source.delete()
        runBlocking { val reopened=Store(app);reopened.refresh();Assert.assertEquals(1,reopened.state.value!!.countdowns.size) }
        val file=Pictures.directory(app).resolve(app.store.state.value!!.countdowns.single().photo!!)
        Assert.assertTrue(file.isFile)
        Assert.assertThrows(Exception::class.java) { Pictures.validate(byteArrayOf(1,2,3)) }
        rule.onNodeWithText("倒计时").performClick();shot("09-custom-photo")
    }
    @Test fun imageBackupRoundTripAndSnapshotRecoveryAreComplete() = runBlocking {
        val bytes=picture();val c=Countdown(id="photo",title="带图",date=today())
        app.store.saveCountdown(c,bytes)
        val original=app.store.exportBundle();val decoded=ArchiveBackup.decode(original)
        Assert.assertEquals(1,decoded.images.size)
        app.store.restoreBundle(BackupBundle(AppState(),emptyMap()))
        Assert.assertTrue(app.store.state.value!!.countdowns.isEmpty())
        Assert.assertTrue(Pictures.directory(app).listFiles()!!.isEmpty())
        val snapshot=ArchiveBackup.decode(app.filesDir.resolve("before-restore.zip").readBytes())
        app.store.restoreBundle(snapshot)
        Assert.assertEquals(decoded.state,app.store.state.value)
        Assert.assertArrayEquals(bytes,Pictures.directory(app).resolve(app.store.state.value!!.countdowns.single().photo!!).readBytes())
        app.store.change { deleteCountdown("photo") };Assert.assertTrue(Pictures.directory(app).listFiles()!!.isEmpty())
        app.store.restoreBundle(decoded);Assert.assertEquals(1,app.store.state.value!!.countdowns.size)
    }
    @Test fun invalidArchivesAndMissingImagesNeverOverwrite() = runBlocking {
        app.store.saveCountdown(Countdown(title="保留",date=today()),picture())
        val before=app.store.export();val archive=app.store.exportBundle()
        Assert.assertThrows(Exception::class.java) { ArchiveBackup.decode(archive.copyOf(archive.size-10)) }
        fun zip(vararg entries:Pair<String,ByteArray>):ByteArray {
            val out=ByteArrayOutputStream();ZipOutputStream(out).use { z -> entries.forEach { (name,data) -> z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry() } };return out.toByteArray()
        }
        Assert.assertThrows(Exception::class.java) { ArchiveBackup.decode(zip("../escape" to byteArrayOf(1))) }
        Assert.assertThrows(Exception::class.java) { ArchiveBackup.decode(zip("data.json" to before.toByteArray())) }
        Assert.assertThrows(Exception::class.java) { ArchiveBackup.decode(zip("data.json" to "bad".toByteArray())) }
        try { app.store.restoreBundle(BackupBundle(Backup.decode(before),emptyMap()));Assert.fail() } catch(_:IllegalArgumentException) { }
        Assert.assertEquals(before,app.store.export())
    }
    @Test fun systemPhotoPickerSelectionAndCancellation() {
        val resolver=app.contentResolver
        val values=android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"countdown-test.jpg");put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/jpeg") }
        val uri=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        resolver.openOutputStream(uri)!!.use { it.write(picture()) }
        try {
            rule.onNodeWithText("倒计时").performClick();rule.onNodeWithContentDescription("新增倒计时").performClick()
            rule.onNodeWithText("事情名称").performTextInput("相册流程")
            rule.onNodeWithText("从相册选择背景").performScrollTo().performClick()
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            rule.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString()=="com.android.documentsui" }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            rule.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString()=="cn.riyousuode.app" }
            rule.onNodeWithText("恢复默认背景").assertDoesNotExist()
            rule.onNodeWithText("从相册选择背景").performScrollTo().performClick()
            rule.waitUntil(10000) { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("countdown-test")?.isNotEmpty()==true }
            val node=automation.rootInActiveWindow.findAccessibilityNodeInfosByText("countdown-test").first()
            Assert.assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            rule.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString()=="cn.riyousuode.app" }
            rule.waitUntil(10000) { runCatching { rule.onAllNodesWithText("恢复默认背景").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
            rule.onNodeWithText("保存").performClick()
            rule.waitUntil(10000) { app.store.state.value!!.countdowns.singleOrNull()?.photo!=null }
            resolver.delete(uri,null,null)
            Assert.assertTrue(Pictures.directory(app).resolve(app.store.state.value!!.countdowns.single().photo!!).isFile)
            shot("10-picked-photo")
        } finally { runCatching { resolver.delete(uri,null,null) } }
    }
    @Test fun oldGoalsRemainReadOnlyAndRewardCanBeFulfilled() {
        runBlocking { app.store.change {
            state.goals.add(Goal(id="legacy",title="旧日目标",type="manual",reward="一本书"))
            state.effects.add(Effect(id="legacy-reward",source="goal:legacy:reward",title="一本书",kind="reward"))
        } }
        rule.onNodeWithText("我的").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("旧目标历史"))
        rule.onNodeWithText("旧目标历史").performScrollTo().performClick()
        rule.onNodeWithText("旧日目标").assertExists();rule.onNodeWithText("编辑").assertDoesNotExist()
        rule.onNodeWithText("奖惩").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("已享用"))
        rule.onNodeWithText("已享用").performScrollTo().performClick();rule.onNodeWithText("确认").performClick()
        rule.waitUntil(10000) { app.store.state.value!!.effects.single().status=="done" }
    }
}


