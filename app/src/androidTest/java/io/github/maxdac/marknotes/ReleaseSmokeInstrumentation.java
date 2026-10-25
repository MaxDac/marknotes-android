package io.github.maxdac.marknotes;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import io.github.maxdac.marknotes.sync.Action;
import io.github.maxdac.marknotes.sync.LocalEntry;
import io.github.maxdac.marknotes.sync.Marknotes_syncKt;
import io.github.maxdac.marknotes.sync.MergeResult;
import io.github.maxdac.marknotes.sync.RemoteFolderDeletion;
import io.github.maxdac.marknotes.sync.SyncInput;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Platform-only runner: a debug AndroidX/Kotlin test runner cannot link against an obfuscated release.
 * Only the explicitly kept UniFFI API is referenced; UI, persistence and import use Android APIs.
 */
public final class ReleaseSmokeInstrumentation extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            smoke();
            result.putString("stream", "RELEASE_SMOKE_OK\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void smoke() throws Exception {
        MergeResult merged = Marknotes_syncKt.merge3("a\nb\nc\n", "A\nb\nc\n", "a\nb\nC\n");
        require(!merged.getHasConflicts() && merged.getText().equals("A\nb\nC\n"), "Native merge failed");
        require(Marknotes_syncKt.plan(new SyncInput(
            Collections.singletonList(new LocalEntry("n.md", false, 1L, "hash")),
            Collections.emptyList(), Collections.emptyList(), null, RemoteFolderDeletion.ATOMIC_EMPTY_ONLY
        )).getActions().stream().anyMatch(action ->
            action instanceof Action.UploadNew && ((Action.UploadNew) action).getPath().equals("n.md")
        ), "Native sync plan failed");
        Context context = getTargetContext();
        File note = new File(context.getFilesDir(), "notes/R8 smoke.md");
        Files.createDirectories(note.toPath().getParent());
        write(note, "# Shrinker smoke\n\n```kotlin\nval answer = 42\n```\n");
        try {
            context.startActivity(Objects.requireNonNull(
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName())
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitFor(() -> {
                AccessibilityNodeInfo skip = find("Skip");
                return skip != null ? skip : find("R8 smoke");
            });
            AccessibilityNodeInfo skip = find("Skip");
            if (skip != null) click(skip);
            click(waitFor(() -> find("R8 smoke")));
            AccessibilityNodeInfo editor = waitFor(() -> walk(
                getUiAutomation().getRootInActiveWindow(), AccessibilityNodeInfo::isEditable
            ));
            Bundle text = new Bundle();
            text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                "# Shrinker smoke\n\n```kotlin\nval answer = 43\n```\n");
            require(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text), "Cannot edit note");
            click(waitFor(() -> find("Preview")));
            AccessibilityNodeInfo preview = waitFor(() -> findContent("val answer = 43"));
            require(preview.getText().toString().contains("Shrinker smoke")
                && !preview.getText().toString().contains("```"), "Markdown preview was not rendered");
            click(waitFor(() -> find("Back")));
            waitFor(() -> find("R8 smoke"));
            require(read(note).contains("val answer = 43"), "Note edit was not saved");
            click(waitFor(() -> find("Settings")));
            waitFor(() -> find("Appearance"));
            getUiAutomation().waitForIdle(500, 5000);
            try (SQLiteDatabase database = SQLiteDatabase.openDatabase(
                context.getDatabasePath("marknotes.db").getPath(), null, SQLiteDatabase.OPEN_READONLY
            ); Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM drive_accounts", null)) {
                require(cursor.moveToFirst() && cursor.getInt(0) == 0, "Room account table is not readable");
            }
            for (int attempt = 0; attempt < 12 && find("Turn on app lock") == null; attempt++) {
                swipeUp(context);
                getUiAutomation().waitForIdle(500, 5000);
            }
            AccessibilityNodeInfo lock = waitFor(() -> find("Turn on app lock"));
            boolean lockEnabled = true;
            while (lock != null) {
                lockEnabled &= lock.isEnabled();
                lock = lock.getParent();
            }
            require(lockEnabled == Objects.requireNonNull(
                context.getSystemService(KeyguardManager.class)
            ).isDeviceSecure(), "App lock does not match device authentication availability");
            click(waitFor(() -> find("Back")));
            importPdf(context);
        } finally {
            Files.deleteIfExists(note.toPath());
            Files.deleteIfExists(new File(context.getFilesDir(), "notes/R8 PDF.md").toPath());
        }
    }

    private void importPdf(Context context) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "R8 PDF.pdf");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        Uri uri = Objects.requireNonNull(context.getContentResolver().insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ));
        try {
            PdfDocument pdf = new PdfDocument();
            try {
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(300, 300, 1).create());
                Paint paint = new Paint();
                paint.setTextSize(14f);
                page.getCanvas().drawText("PDF shrinker smoke", 20f, 40f, paint);
                pdf.finishPage(page);
                try (OutputStream stream = Objects.requireNonNull(context.getContentResolver().openOutputStream(uri))) {
                    pdf.writeTo(stream);
                }
            } finally {
                pdf.close();
            }
            context.startActivity(new Intent(Intent.ACTION_SEND)
                .setPackage(context.getPackageName()).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
            waitFor(() -> findContent("PDF shrinker smoke"));
            click(waitFor(() -> find("Save")));
            waitFor(() -> find("Back"));
            File imported = new File(context.getFilesDir(), "notes/R8 PDF.md");
            require(read(imported).contains("PDF shrinker smoke"), "PDF import failed");
        } finally {
            context.getContentResolver().delete(uri, null, null);
        }
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private void swipeUp(Context context) {
        float x = context.getResources().getDisplayMetrics().widthPixels * 0.5f;
        float height = context.getResources().getDisplayMetrics().heightPixels;
        long start = SystemClock.uptimeMillis();
        for (int step = 0; step <= 10; step++) {
            int action = step == 0 ? MotionEvent.ACTION_DOWN
                : step == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(),
                action, x, height * (0.8f - step * 0.05f), 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                require(getUiAutomation().injectInputEvent(event, true), "Cannot swipe Settings");
            } finally {
                event.recycle();
            }
            SystemClock.sleep(30);
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private AccessibilityNodeInfo findContent(String text) {
        return walk(getUiAutomation().getRootInActiveWindow(),
            node -> node.getText() != null && node.getText().toString().contains(text));
    }

    private AccessibilityNodeInfo find(String label) {
        return walk(getUiAutomation().getRootInActiveWindow(), node ->
            label.contentEquals(node.getText() == null ? "" : node.getText())
                || label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()));
    }

    private static AccessibilityNodeInfo walk(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate) {
        if (node == null) return null;
        if (predicate.test(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = walk(node.getChild(i), predicate);
            if (found != null) return found;
        }
        return null;
    }

    private static void click(AccessibilityNodeInfo node) {
        while (node != null && !node.isClickable()) node = node.getParent();
        require(node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Cannot click UI element");
    }

    private AccessibilityNodeInfo waitFor(Supplier<AccessibilityNodeInfo> find) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        do {
            AccessibilityNodeInfo node = find.get();
            if (node != null) return node;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        StringBuilder labels = new StringBuilder();
        walk(getUiAutomation().getRootInActiveWindow(), node -> {
            labels.append(node.getText()).append(" / ").append(node.getContentDescription()).append("\n");
            return false;
        });
        throw new AssertionError("Release UI did not become ready:\n" + labels);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
