package com.mowtiie.supanote.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.Theme;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.repository.AuthRepository;
import com.mowtiie.supanote.data.repository.NoteRepository;
import com.mowtiie.supanote.databinding.ActivitySettingsBinding;
import com.mowtiie.supanote.ui.SupanoteActivity;
import com.mowtiie.supanote.ui.setup.SetupActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends SupanoteActivity {

    private ActivitySettingsBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom);
            return insets;
        });

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private static final String TAG_EXPORT = "SettingsExport";
        private static final String TAG_IMPORT = "SettingsImport";

        private static final int BACKUP_VERSION = 1;
        private static final String KEY_VERSION = "version";
        private static final String KEY_EXPORTED_AT = "exportedAt";
        private static final String KEY_NOTES = "notes";
        private static final String KEY_TITLE = "title";
        private static final String KEY_CONTENT = "content";
        private static final String KEY_CREATED_AT = "createdAt";

        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private AlertDialog progressDialog;

        private Preference exportNotes;
        private Preference importNotes;
        private Preference changeServer;

        private ListPreference listContrast;
        private ListPreference listTheme;

        private SwitchPreferenceCompat switchDynamicColors;
        private SwitchPreferenceCompat switchScreenPrivacy;

        private final ActivityResultLauncher<Intent> exportDataLauncher =
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null) {
                            exportJSON(data.getData());
                        }
                    }
                });

        private final ActivityResultLauncher<Intent> importDataLauncher =
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null) {
                            importJSON(data.getData());
                        }
                    }
                });

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            setPreferencesFromResource(R.xml.preferences_settings, rootKey);
            findPreferences();

            listTheme.setOnPreferenceChangeListener((preference, newValue) -> {
                String selectedTheme = (String) newValue;
                if (selectedTheme.equals(Theme.SYSTEM.VALUE)) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                } else if (selectedTheme.equals(Theme.BATTERY.VALUE)){
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
                } else if (selectedTheme.equals(Theme.LIGHT.VALUE)){
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                } else if (selectedTheme.equals(Theme.DARK.VALUE)) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                }
                return true;
            });

            listContrast.setOnPreferenceChangeListener((preference, newValue) -> {
                requireActivity().recreate();
                return true;
            });

            switchDynamicColors.setVisible(DynamicColors.isDynamicColorAvailable());
            switchDynamicColors.setOnPreferenceChangeListener((preference, newValue) -> {
                requireActivity().recreate();
                return true;
            });

            switchScreenPrivacy.setOnPreferenceChangeListener((preference, newValue) -> {
                requireActivity().recreate();
                return true;
            });

            exportNotes.setOnPreferenceClickListener(preference -> {
                exportData();
                return true;
            });

            importNotes.setOnPreferenceClickListener(preference -> {
                importData();
                return true;
            });

            changeServer.setOnPreferenceClickListener(preference -> {
                confirmChangeServer();
                return true;
            });
        }

        @Override
        public void onDestroyView() {
            super.onDestroyView();
            dismissProgressDialog();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            executor.shutdown();
        }

        private void findPreferences() {
            exportNotes = findPreference("export");
            importNotes = findPreference("import");
            changeServer = findPreference("change_server");
            listTheme = findPreference("theme");
            listContrast = findPreference("contrast");
            switchDynamicColors = findPreference("dynamic_colors");
            switchScreenPrivacy = findPreference("screen_privacy");
        }

        private void exportData() {
            showProgressDialog(R.string.dialog_message_progress_exporting);
            SupanoteApp app = (SupanoteApp) requireActivity().getApplication();
            NoteRepository repo = new NoteRepository(app.connection(), app.session(), app.authedHttpClient());

            repo.getNotes(new NoteRepository.Callback<List<Note>>() {
                @Override public void onSuccess(List<Note> notes) {
                    postToUi(() -> {
                        dismissProgressDialog();
                        if (notes == null || notes.isEmpty()) {
                            Toast.makeText(requireContext(),
                                    R.string.toast_export_no_notes,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        pendingExport = notes;
                        Intent exportIntent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        exportIntent.addCategory(Intent.CATEGORY_OPENABLE);
                        exportIntent.setType("application/json");
                        exportIntent.putExtra(Intent.EXTRA_TITLE, "supanote_backup.json");
                        exportDataLauncher.launch(exportIntent);
                    });
                }

                @Override public void onError(Exception e) {
                    Log.e(TAG_EXPORT, "Load for export failed", e);
                    postToUi(() -> {
                        dismissProgressDialog();
                        Toast.makeText(requireContext(),
                                R.string.toast_export_failed,
                                Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }

        private List<Note> pendingExport;

        private void exportJSON(Uri uri) {
            if (pendingExport == null) return;
            List<Note> notes = pendingExport;
            pendingExport = null;

            showProgressDialog(R.string.dialog_message_progress_exporting);
            Context appContext = requireContext().getApplicationContext();
            executor.execute(() -> {
                boolean success = performExport(appContext, uri, notes);
                postToUi(() -> {
                    dismissProgressDialog();
                    Toast.makeText(requireContext(),
                            success ? R.string.toast_export_success : R.string.toast_export_failed,
                            Toast.LENGTH_SHORT).show();
                });
            });
        }

        private boolean performExport(Context context, Uri uri, List<Note> notes) {
            // Streamed note by note so the whole backup never has to exist as one string in memory.
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    Log.e(TAG_EXPORT, "openOutputStream returned null for " + uri);
                    return false;
                }

                JsonWriter writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)));
                writer.setIndent("  ");
                writer.beginObject();
                writer.name(KEY_VERSION).value(BACKUP_VERSION);
                writer.name(KEY_EXPORTED_AT).value(System.currentTimeMillis());
                writer.name(KEY_NOTES).beginArray();
                for (Note note : notes) {
                    writer.beginObject();
                    writer.name(KEY_TITLE).value(note.getTitle() == null ? "" : note.getTitle());
                    writer.name(KEY_CONTENT).value(note.getContent() == null ? "" : note.getContent());
                    if (note.getCreatedAt() != null) {
                        writer.name(KEY_CREATED_AT).value(note.getCreatedAt());
                    }
                    writer.endObject();
                }
                writer.endArray();
                writer.endObject();
                writer.flush();
                return true;
            } catch (Exception e) {
                Log.e(TAG_EXPORT, "Export failed", e);
                return false;
            }
        }

        private void importData() {
            Intent importIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            importIntent.addCategory(Intent.CATEGORY_OPENABLE);
            importIntent.setType("application/json");
            importDataLauncher.launch(importIntent);
        }

        private void importJSON(Uri uri) {
            showProgressDialog(R.string.dialog_message_progress_importing);
            Context appContext = requireContext().getApplicationContext();
            executor.execute(() -> {
                List<JSONObject> parsed;
                try {
                    parsed = parseImportFile(appContext, uri);
                } catch (Exception e) {
                    Log.e(TAG_IMPORT, "Could not parse import file", e);
                    postToUi(() -> {
                        dismissProgressDialog();
                        Toast.makeText(requireContext(),
                                R.string.toast_import_invalid_file,
                                Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                if (parsed.isEmpty()) {
                    postToUi(() -> {
                        dismissProgressDialog();
                        Toast.makeText(requireContext(),
                                R.string.toast_import_invalid_file,
                                Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                postToUi(() -> {
                    dismissProgressDialog();
                    confirmAndImport(parsed);
                });
            });
        }

        private List<JSONObject> parseImportFile(Context context, Uri uri) throws Exception {
            try (InputStream rawStream = context.getContentResolver().openInputStream(uri)) {
                if (rawStream == null) {
                    throw new Exception("openInputStream returned null for " + uri);
                }

                JsonReader reader = new JsonReader(new BufferedReader(
                        new InputStreamReader(rawStream, StandardCharsets.UTF_8)));
                List<JSONObject> notes = new ArrayList<>();
                boolean foundNotes = false;

                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (KEY_VERSION.equals(name)) {
                        int version = reader.nextInt();
                        if (version > BACKUP_VERSION) {
                            throw new Exception("Backup version " + version + " is newer than this app understands");
                        }
                    } else if (KEY_NOTES.equals(name)) {
                        foundNotes = true;
                        reader.beginArray();
                        while (reader.hasNext()) {
                            JSONObject note = readNote(reader);
                            if (note != null) notes.add(note);
                        }
                        reader.endArray();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();

                if (!foundNotes) throw new Exception("Backup has no notes array");
                return notes;
            }
        }

        /** Reads one backup entry, keeping only title and content; returns null for entries without a title. */
        private JSONObject readNote(JsonReader reader) throws Exception {
            String title = null;
            String content = null;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (KEY_TITLE.equals(name) && reader.peek() == JsonToken.STRING) {
                    title = reader.nextString();
                } else if (KEY_CONTENT.equals(name) && reader.peek() == JsonToken.STRING) {
                    content = reader.nextString();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();

            if (title == null || title.trim().isEmpty()) return null;
            return new JSONObject()
                    .put(KEY_TITLE, title)
                    .put(KEY_CONTENT, content == null ? "" : content);
        }

        private void confirmAndImport(List<JSONObject> notes) {
            if (!isAdded()) return;
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_title_import_confirm)
                    .setMessage(getResources().getQuantityString(R.plurals.dialog_message_import_confirm, notes.size(), notes.size()))
                    .setPositiveButton(R.string.button_import, (d, w) -> uploadImportedNotes(notes))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void uploadImportedNotes(List<JSONObject> notes) {
            showProgressDialog(R.string.dialog_message_progress_importing);
            SupanoteApp app = (SupanoteApp) requireActivity().getApplication();
            NoteRepository repo = new NoteRepository(app.connection(), app.session(), app.authedHttpClient());

            repo.bulkInsert(notes, new NoteRepository.Callback<Integer>() {
                @Override public void onSuccess(Integer count) {
                    if (!isAdded()) return;
                    dismissProgressDialog();
                    Toast.makeText(requireContext(),
                            getResources().getQuantityString(R.plurals.toast_import_success, count, count),
                            Toast.LENGTH_SHORT).show();
                }
                @Override public void onError(Exception e) {
                    Log.e(TAG_IMPORT, "Bulk import failed", e);
                    if (!isAdded()) return;
                    dismissProgressDialog();
                    String message = e instanceof NoteRepository.PartialImportException
                            ? getString(R.string.toast_import_partial,
                                    ((NoteRepository.PartialImportException) e).imported,
                                    ((NoteRepository.PartialImportException) e).total)
                            : getString(R.string.toast_import_failed);
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                }
            });
        }

        private void confirmChangeServer() {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_title_change_server_confirm)
                    .setMessage(R.string.dialog_message_change_server_confirm)
                    .setPositiveButton(R.string.button_changer_server_continue, (d, w) -> {
                        SupanoteApp app = (SupanoteApp) requireActivity().getApplication();
                        // Revoke the session on the old server (best effort) before forgetting it.
                        new AuthRepository(app.connection(), app.session(), app.httpClient()).signOut();
                        app.connection().clear();
                        Intent intent = new Intent(requireContext(), SetupActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void showProgressDialog(@StringRes int messageRes) {
            if (!isAdded()) return;
            dismissProgressDialog();

            View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_progress, null, false);
            MaterialTextView progressMessage = view.findViewById(R.id.progress_message);
            progressMessage.setText(messageRes);

            progressDialog = new MaterialAlertDialogBuilder(requireContext())
                    .setView(view)
                    .setCancelable(false)
                    .create();
            progressDialog.show();
        }

        private void dismissProgressDialog() {
            if (progressDialog != null) {
                if (progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                progressDialog = null;
            }
        }

        private void postToUi(Runnable runnable) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (isAdded()) {
                    runnable.run();
                }
            });
        }
    }
}