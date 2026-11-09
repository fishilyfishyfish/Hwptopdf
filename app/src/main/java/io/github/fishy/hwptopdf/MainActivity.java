package io.github.fishy.hwptopdf;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String EXTRA_PICK = "pick";
    static final String EXTRA_OPEN_LAST = "open_last";
    private static final String EXTRA_E2E_PATH = "e2e_path";

    private static final String TAG = "HwpToPdf";
    private static final int REQUEST_PICK = 1;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String INPUT_PATH = "/input/doc";
    private static final String PREFS = "last";
    private static final String KEY_URI = "uri";
    private static final String KEY_NAME = "name";
    private static final String KEY_HIGH_QUALITY = "high_quality";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private FrameLayout webHost;
    private WebView web;
    private TextView status;
    private ProgressBar progress;
    private Button pickButton;
    private Switch qualitySwitch;
    private View resultRow;

    private File inputFile;
    private boolean engineReady;
    private boolean waitingForEngine;
    private boolean busy;
    private boolean e2eRun;

    // Written from the JavaScript bridge thread, one call at a time.
    private volatile PdfWriter writer;
    private volatile Uri outputUri;
    private volatile String outputName = "";
    private volatile File e2eOutput;
    private volatile int pageTotal;
    private volatile boolean writeFailed;

    static String lastName(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NAME, null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inputFile = new File(getCacheDir(), "input.bin");
        setContentView(buildScreen());
        createWebView();
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @Override
    protected void onDestroy() {
        abandonOutput();
        if (web != null) {
            web.destroy();
        }
        io.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            start(data.getData());
        }
    }

    private void handle(Intent intent) {
        if (intent == null) {
            return;
        }
        // An intent is handled once, even if the activity is recreated later.
        setIntent(new Intent());
        if (intent.getBooleanExtra(EXTRA_OPEN_LAST, false)) {
            openLast();
            return;
        }
        String e2ePath = intent.getStringExtra(EXTRA_E2E_PATH);
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (e2ePath != null && debuggable) {
            startE2e(e2ePath);
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            start(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                start(stream);
            } else {
                status.setText(R.string.no_file_received);
            }
        } else if (intent.getBooleanExtra(EXTRA_PICK, false)) {
            pickFile();
        }
    }

    private void pickFile() {
        if (busy) {
            Toast.makeText(this, R.string.already_converting, Toast.LENGTH_SHORT).show();
            return;
        }
        // HWP files often report a generic type, so filtering by type would hide them.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(intent, REQUEST_PICK);
    }

    private void start(Uri source) {
        if (busy) {
            Toast.makeText(this, R.string.already_converting, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        status.setText(R.string.reading_file);
        io.execute(() -> {
            try {
                outputName = pdfName(displayName(source));
                try (InputStream in = getContentResolver().openInputStream(source);
                     OutputStream out = new FileOutputStream(inputFile)) {
                    copy(in, out);
                }
                runOnUiThread(this::convertWhenReady);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Could not read the input", e);
                runOnUiThread(() -> finishWithError(getString(R.string.read_failed)));
            }
        });
    }

    private void startE2e(String relativePath) {
        e2eRun = true;
        setBusy(true);
        outputName = "e2e.pdf";
        e2eOutput = new File(getCacheDir(), "e2e.pdf");
        io.execute(() -> {
            try (InputStream in = new FileInputStream(new File(getCacheDir(), relativePath));
                 OutputStream out = new FileOutputStream(inputFile)) {
                copy(in, out);
                runOnUiThread(this::convertWhenReady);
            } catch (IOException e) {
                Log.e(TAG, "e2e input", e);
                runOnUiThread(() -> finishWithError(String.valueOf(e)));
            }
        });
    }

    private void convertWhenReady() {
        if (!engineReady) {
            waitingForEngine = true;
            status.setText(R.string.loading_engine);
            return;
        }
        waitingForEngine = false;
        status.setText(R.string.opening_document);
        boolean high = qualitySwitch.isChecked();
        web.evaluateJavascript("convert(" + (high ? "3.75,0.9" : "2.5,0.85") + ")", null);
    }

    private void setBusy(boolean value) {
        busy = value;
        pickButton.setEnabled(!value);
        qualitySwitch.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) {
            progress.setIndeterminate(true);
            resultRow.setVisibility(View.GONE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void finishWithError(String message) {
        setBusy(false);
        status.setText(getString(R.string.failed, message));
        markE2eDone();
    }

    private void markE2eDone() {
        if (!e2eRun) {
            return;
        }
        try {
            new File(getCacheDir(), "e2e.done").createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "e2e marker", e);
        }
    }

    // ---- Output ----

    private void openOutput() throws IOException {
        OutputStream target;
        if (e2eOutput != null) {
            target = new FileOutputStream(e2eOutput);
        } else {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, outputName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HWP2PDF");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Downloads entry was not created");
            }
            outputUri = uri;
            target = getContentResolver().openOutputStream(uri);
            if (target == null) {
                throw new IOException("Downloads entry could not be opened");
            }
        }
        writer = new PdfWriter(new BufferedOutputStream(target, 1 << 16));
    }

    private void publishOutput() throws IOException {
        PdfWriter current = writer;
        writer = null;
        current.finish();
        current.close();
        if (outputUri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(outputUri, values, null, null);
        }
    }

    /** Removes a half-written file so a failed run leaves nothing in Downloads. */
    private void abandonOutput() {
        PdfWriter current = writer;
        writer = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                Log.w(TAG, "Closing a partial file", e);
            }
            if (outputUri != null) {
                getContentResolver().delete(outputUri, null, null);
                outputUri = null;
            }
        }
    }

    private void onConverted() {
        setBusy(false);
        status.setText(getString(R.string.saved, outputName));
        markE2eDone();
        if (outputUri == null) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_URI, outputUri.toString())
                .putString(KEY_NAME, outputName)
                .apply();
        ConvertWidget.refresh(this);
        resultRow.setVisibility(View.VISIBLE);
    }

    private void openLast() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URI, null);
        if (stored == null) {
            status.setText(R.string.no_recent);
            return;
        }
        view(Uri.parse(stored));
    }

    private void view(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            status.setText(R.string.cannot_open);
        }
    }

    private void share(Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, outputName));
    }

    // ---- WebView engine ----

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        engineReady = false;
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serve(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                rebuildAfterCrash();
                return true;
            }
        });
        webHost.addView(web, new FrameLayout.LayoutParams(1, 1));
        web.loadUrl("https://" + HOST + "/index.html");
    }

    private void rebuildAfterCrash() {
        abandonOutput();
        webHost.removeAllViews();
        web.destroy();
        createWebView();
        if (busy) {
            finishWithError(getString(R.string.out_of_memory));
        }
    }

    /** Everything comes from the app itself; any other request is refused. */
    private WebResourceResponse serve(Uri url) {
        String path = url.getPath();
        if (!HOST.equals(url.getHost()) || path == null || path.contains("..")) {
            return notFound();
        }
        try {
            if (INPUT_PATH.equals(path)) {
                return new WebResourceResponse("application/octet-stream", null, new FileInputStream(inputFile));
            }
            return new WebResourceResponse(mimeType(path), null, getAssets().open(path.substring(1)));
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeType(String path) {
        if (path.endsWith(".html")) {
            return "text/html";
        }
        if (path.endsWith(".js")) {
            return "text/javascript";
        }
        if (path.endsWith(".wasm")) {
            return "application/wasm";
        }
        if (path.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "application/octet-stream";
    }

    /** Called from JavaScript on a background thread. */
    private final class Bridge {
        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> {
                engineReady = true;
                if (waitingForEngine) {
                    convertWhenReady();
                }
            });
        }

        @JavascriptInterface
        public void begin(int pages) {
            pageTotal = pages;
            writeFailed = false;
            try {
                openOutput();
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Could not create the PDF", e);
                writeFailed = true;
            }
            runOnUiThread(() -> {
                progress.setIndeterminate(false);
                progress.setMax(pages);
                progress.setProgress(0);
            });
        }

        @JavascriptInterface
        public void page(int index, String jpegBase64, int pixelWidth, int pixelHeight,
                         double widthPt, double heightPt, String textOps) {
            PdfWriter current = writer;
            if (writeFailed || current == null) {
                return;
            }
            try {
                current.addPage(Base64.decode(jpegBase64, Base64.NO_WRAP),
                        pixelWidth, pixelHeight, widthPt, heightPt, textOps);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Could not write page " + index, e);
                writeFailed = true;
            }
            int done = index + 1;
            runOnUiThread(() -> {
                progress.setProgress(done);
                status.setText(getString(R.string.converting, done, pageTotal));
            });
        }

        @JavascriptInterface
        public void done() {
            boolean ok = !writeFailed;
            if (ok) {
                try {
                    publishOutput();
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "Could not finish the PDF", e);
                    ok = false;
                }
            }
            boolean saved = ok;
            if (!saved) {
                abandonOutput();
            }
            runOnUiThread(() -> {
                if (saved) {
                    onConverted();
                } else {
                    finishWithError(getString(R.string.write_failed));
                }
            });
        }

        @JavascriptInterface
        public void fail(String message) {
            abandonOutput();
            runOnUiThread(() -> finishWithError(message));
        }
    }

    // ---- Files ----

    private String displayName(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "No display name", e);
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "document" : segment;
    }

    private static String pdfName(String source) {
        String name = source.substring(source.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return (name.isEmpty() ? "document" : name) + ".pdf";
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        if (in == null) {
            throw new IOException("Input stream was null");
        }
        byte[] buffer = new byte[1 << 16];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    // ---- Screen ----

    private View buildScreen() {
        int ink = Color.rgb(0x1f, 0x29, 0x33);
        int muted = Color.rgb(0x6b, 0x72, 0x80);
        int accent = Color.rgb(0x1f, 0x5f, 0xa8);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(0xf6, 0xf5, 0xf1));
        root.setFitsSystemWindows(true);

        // Stays a readable width on a tablet instead of stretching across it.
        LinearLayout column = new LinearLayout(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int limit = MeasureSpec.makeMeasureSpec(
                        Math.min(MeasureSpec.getSize(widthSpec), dp(480)), MeasureSpec.EXACTLY);
                super.onMeasure(limit, heightSpec);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(ink);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);

        status = new TextView(this);
        status.setText(R.string.choose_prompt);
        status.setTextColor(ink);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(0, dp(12), 0, dp(12));
        column.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        progress.setProgressTintList(ColorStateList.valueOf(accent));
        progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        column.addView(progress);

        pickButton = new Button(this);
        pickButton.setText(R.string.choose_file);
        pickButton.setTextColor(Color.WHITE);
        pickButton.setBackgroundTintList(ColorStateList.valueOf(accent));
        pickButton.setOnClickListener(v -> pickFile());
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        pickParams.topMargin = dp(8);
        column.addView(pickButton, pickParams);

        qualitySwitch = new Switch(this);
        qualitySwitch.setText(R.string.high_quality);
        qualitySwitch.setTextColor(ink);
        qualitySwitch.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_HIGH_QUALITY, false));
        qualitySwitch.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_HIGH_QUALITY, checked).apply());
        qualitySwitch.setPadding(0, dp(16), 0, dp(16));
        column.addView(qualitySwitch);

        LinearLayout row = new LinearLayout(this);
        row.setVisibility(View.GONE);
        resultRow = row;
        Button open = new Button(this);
        open.setText(R.string.open);
        open.setOnClickListener(v -> {
            if (outputUri != null) {
                view(outputUri);
            }
        });
        Button send = new Button(this);
        send.setText(R.string.share);
        send.setOnClickListener(v -> {
            if (outputUri != null) {
                share(outputUri);
            }
        });
        row.addView(open, new LinearLayout.LayoutParams(0, dp(52), 1));
        row.addView(send, new LinearLayout.LayoutParams(0, dp(52), 1));
        column.addView(row);

        TextView note = new TextView(this);
        note.setText(R.string.footer);
        note.setTextColor(muted);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(0, dp(20), 0, 0);
        column.addView(note);

        // The engine needs a live WebView, but nobody needs to see it.
        webHost = new FrameLayout(this);
        column.addView(webHost, new LinearLayout.LayoutParams(1, 1));
        return root;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
