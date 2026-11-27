package kr.magi.gnsskeeper;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowContentResolver;

/** Android API31/API36 simulation. This is not a physical Galaxy test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {31, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public final class LogExportActivityTest {
    private ActivityController<LogExportActivity> controller;
    private File session;
    private Context app;
    @Before public void before() throws Exception {
        app = RuntimeEnvironment.getApplication();
        GnssSnapshot.phase = "STOPPED"; GnssSnapshot.running = false; GnssSnapshot.observerRegistered = false;
        session = new File(app.getExternalFilesDir("gnss_logs"), "PASSIVE_20261103_070000_" + Long.toUnsignedString(System.nanoTime()));
        assertTrue(session.mkdirs());
        Files.write(new File(session, "observer_passive.csv").toPath(), "provider,lat,lon\nnetwork,1.25,2.5\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(session, "session.json").toPath(), "{\"state\":\"CLOSED\"}".getBytes(StandardCharsets.UTF_8));
    }
    @After public void after() {
        if (controller != null) controller.pause().stop().destroy();
        GnssSnapshot.phase = "STOPPED"; GnssSnapshot.running = false; GnssSnapshot.observerRegistered = false;
    }
    private LogExportActivity start() throws Exception {
        controller = Robolectric.buildActivity(LogExportActivity.class).setup();
        LogExportActivity activity = controller.get();
        waitFor(() -> ((Button) activity.findViewById(R.id.exportSave)).isEnabled());
        return activity;
    }
    private ShadowActivity.IntentForResult chooseAll(LogExportActivity activity) throws Exception {
        ((CheckBox) activity.findViewById(R.id.exportSelectAll)).performClick();
        activity.findViewById(R.id.exportSave).performClick();
        final ShadowActivity.IntentForResult[] launched = new ShadowActivity.IntentForResult[1];
        waitFor(() -> { launched[0] = shadowOf(activity).getNextStartedActivityForResult(); return launched[0] != null; });
        return launched[0];
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); if (condition.getAsBoolean()) return; Thread.sleep(15);
        }
        fail("Timed out waiting for asynchronous export UI");
    }
    private String status(LogExportActivity a) { return ((TextView)a.findViewById(R.id.exportStatus)).getText().toString(); }
    @Test public void activeRecordingBlocksExportWithoutStoppingIt() {
        GnssSnapshot.phase = "RUNNING"; GnssSnapshot.running = true; GnssSnapshot.observerRegistered = true;
        controller = Robolectric.buildActivity(LogExportActivity.class).setup();
        assertFalse(controller.get().findViewById(R.id.exportSave).isEnabled());
        assertTrue(status(controller.get()).contains("중지")); assertTrue(GnssSnapshot.running);
        assertNull(shadowOf(controller.get()).getNextStartedActivityForResult());
    }
    @Test public void pickerRequestsSingleZipWithoutBroadStorageGrant() throws Exception {
        LogExportActivity a = start(); ShadowActivity.IntentForResult request = chooseAll(a);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.getAction());
        assertEquals("application/zip", request.intent.getType());
        assertTrue(request.intent.getCategories().contains(Intent.CATEGORY_OPENABLE));
        assertTrue(request.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".zip"));
        assertFalse(a.findViewById(R.id.exportSave).isEnabled());
    }
    @Test public void cancellingPickerKeepsOriginalAndAllowsRetry() throws Exception {
        LogExportActivity a = start(); ShadowActivity.IntentForResult request = chooseAll(a);
        byte[] before = Files.readAllBytes(new File(session, "observer_passive.csv").toPath());
        a.onActivityResult(request.requestCode, Activity.RESULT_CANCELED, null);
        waitFor(() -> a.findViewById(R.id.exportSave).isEnabled());
        assertTrue(status(a).contains("취소"));
        assertArrayEquals(before, Files.readAllBytes(new File(session, "observer_passive.csv").toPath()));
    }
    @Test public void recreationWhilePickerOpenDoesNotLaunchSecondPicker() throws Exception {
        LogExportActivity old = start(); ShadowActivity.IntentForResult request = chooseAll(old);
        controller.recreate(); LogExportActivity recreated = controller.get();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(recreated).getNextStartedActivityForResult());
        recreated.onActivityResult(request.requestCode, Activity.RESULT_CANCELED, null);
        waitFor(() -> recreated.findViewById(R.id.exportSave).isEnabled());
        assertTrue(status(recreated).contains("취소"));
    }
    @Test public void selectedDocumentIsSavedAndReadBackBeforeSuccess() throws Exception {
        LocalProvider provider = new LocalProvider();
        provider.target = File.createTempFile("synthetic-saved-", ".zip", app.getCacheDir());
        ProviderInfo info = new ProviderInfo(); info.authority = "kr.magi.gnsskeeper.exportfixture";
        provider.attachInfo(app, info); ShadowContentResolver.registerProviderInternal(info.authority, provider);
        LogExportActivity a = start(); ShadowActivity.IntentForResult request = chooseAll(a);
        a.onActivityResult(request.requestCode, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://" + info.authority + "/saved")));
        waitFor(() -> status(a).contains("정합성을 확인했습니다"));
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(provider.target)) {
            assertNotNull(zip.getEntry("external/" + session.getName() + "/observer_passive.csv"));
            assertNotNull(zip.getEntry("export_manifest.json"));
        }
        assertTrue(new File(session, "observer_passive.csv").exists());
    }
    @Test public void destinationOpenFailureIsVisibleAndDoesNotDeleteOriginal() throws Exception {
        LocalProvider provider = new LocalProvider(); provider.fail = true;
        ProviderInfo info = new ProviderInfo(); info.authority = "kr.magi.gnsskeeper.exportfixture";
        provider.attachInfo(app, info); ShadowContentResolver.registerProviderInternal(info.authority, provider);
        LogExportActivity a = start(); ShadowActivity.IntentForResult request = chooseAll(a);
        a.onActivityResult(request.requestCode, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://" + info.authority + "/failed")));
        waitFor(() -> status(a).contains("실패했습니다"));
        assertFalse(status(a).contains("정합성을 확인했습니다")); assertTrue(new File(session, "observer_passive.csv").exists());
    }
    public static final class LocalProvider extends ContentProvider {
        File target; boolean fail;
        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (fail) throw new FileNotFoundException("Synthetic provider failure");
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String sort) { return null; }
        @Override public String getType(Uri uri) { return "application/zip"; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String s, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String s, String[] args) { return 0; }
    }
}
