package com.mowtiie.supanote.ui.setup;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;
import com.mowtiie.supanote.R;
import com.mowtiie.supanote.SupanoteApp;
import com.mowtiie.supanote.data.model.Note;
import com.mowtiie.supanote.data.repository.NoteRepository;
import com.mowtiie.supanote.databinding.ActivitySettingsBinding;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SettingsActivity extends AppCompatActivity {

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
        }

        private void exportData() {
            showProgressDialog(R.string.progress_exporting);
            Context appContext = requireContext().getApplicationContext();
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

            showProgressDialog(R.string.progress_exporting);
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
            try {
                JSONObject root = new JSONObject();
                root.put(KEY_VERSION, BACKUP_VERSION);
                root.put(KEY_EXPORTED_AT, System.currentTimeMillis());

                JSONArray notesArray = new JSONArray();
                for (Note note : notes) {
                    JSONObject noteObject = new JSONObject();
                    noteObject.put(KEY_TITLE, note.getTitle());
                    noteObject.put(KEY_CONTENT,
                            note.getContent() == null ? "" : note.getContent());
                    if (note.getCreatedAt() != null) {
                        noteObject.put(KEY_CREATED_AT, note.getCreatedAt());
                    }
                    notesArray.put(noteObject);
                }
                root.put(KEY_NOTES, notesArray);

                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        Log.e(TAG_EXPORT, "openOutputStream returned null for " + uri);
                        return false;
                    }
                    out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                }
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
            showProgressDialog(R.string.progress_importing);
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
            String jsonContent;
            try (InputStream rawStream = context.getContentResolver().openInputStream(uri)) {
                if (rawStream == null) {
                    throw new Exception("openInputStream returned null for " + uri);
                }
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(rawStream, StandardCharsets.UTF_8));
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
                jsonContent = builder.toString();
            }

            JSONObject root = new JSONObject(jsonContent);
            JSONArray notesArray = root.getJSONArray(KEY_NOTES);
            List<JSONObject> notes = new ArrayList<>(notesArray.length());
            for (int i = 0; i < notesArray.length(); i++) {
                JSONObject obj = notesArray.getJSONObject(i);
                if (obj.optString(KEY_TITLE, "").trim().isEmpty()) continue;
                notes.add(obj);
            }
            return notes;
        }

        private void confirmAndImport(List<JSONObject> notes) {
            if (!isAdded()) return;
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.import_confirm_title)
                    .setMessage(getString(R.string.import_confirm_message, notes.size()))
                    .setPositiveButton(R.string.import_confirm_button,
                            (d, w) -> uploadImportedNotes(notes))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void uploadImportedNotes(List<JSONObject> notes) {
            showProgressDialog(R.string.progress_importing);
            SupanoteApp app = (SupanoteApp) requireActivity().getApplication();
            NoteRepository repo = new NoteRepository(app.connection(), app.session(), app.authedHttpClient());

            repo.bulkInsert(notes, new NoteRepository.Callback<Integer>() {
                @Override public void onSuccess(Integer count) {
                    if (!isAdded()) return;
                    dismissProgressDialog();
                    Toast.makeText(requireContext(),
                            getString(R.string.toast_import_success, count),
                            Toast.LENGTH_SHORT).show();
                }
                @Override public void onError(Exception e) {
                    Log.e(TAG_IMPORT, "Bulk import failed", e);
                    if (!isAdded()) return;
                    dismissProgressDialog();
                    Toast.makeText(requireContext(),
                            R.string.toast_import_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        private void confirmChangeServer() {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.change_server_confirm_title)
                    .setMessage(R.string.change_server_confirm_message)
                    .setPositiveButton(R.string.change_server_confirm_button, (d, w) -> {
                        SupanoteApp app = (SupanoteApp) requireActivity().getApplication();
                        app.session().clear();
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