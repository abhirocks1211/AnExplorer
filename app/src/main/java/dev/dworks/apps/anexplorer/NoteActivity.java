package dev.dworks.apps.anexplorer;

/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

import com.github.mrengineer13.snackbar.SnackBar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Timer;
import java.util.TimerTask;

import dev.dworks.apps.anexplorer.misc.AsyncTask;
import dev.dworks.apps.anexplorer.misc.FileUtils;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.provider.RootedStorageProvider;
import dev.dworks.apps.anexplorer.root.RootCommands;

public class NoteActivity extends ActionBarActivity implements TextWatcher {

    private EditText mInput;
    private String mOriginal;
    private Timer mTimer;
    private boolean mModified;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note);

        mInput = (EditText) findViewById(R.id.input);
        mInput.addTextChangedListener(this);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getName();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (getIntent().getData() != null) {
            load();
        }
        else {
            mInput.setVisibility(View.VISIBLE);
        }
    }

    private void load() {
        new LoadContent(getIntent().getData()).execute();
    }

    private void save(boolean exitAfter) {
        new SaveContent(getIntent().getData(), exitAfter).execute();
    }

    @Override
    public void onBackPressed() {
        checkUnsavedChanges();
    }

    private void checkUnsavedChanges() {
        if (mOriginal != null && !mOriginal.equals(mInput.getText().toString())) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.unsaved_changes)
                    .setMessage(R.string.unsaved_changes_desc)
                    .setCancelable(false)
                    .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int did) {
                            dialog.dismiss();
                            save(true);
                        }
                    }).setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int did) {
                            dialog.dismiss();
                            finish();
                        }
                    });
            builder.create().show();
        } else {
            finish();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.note_options, menu);
        menu.findItem(R.id.menu_save).setVisible(mModified);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()){
            case android.R.id.home:
                checkUnsavedChanges();
                break;
            case R.id.menu_save:
                save(false);
                break;
            case R.id.menu_revert:
                setSaveProgress(true);
                try {
                    mInput.setText(mOriginal);
                } catch (OutOfMemoryError e){
                    showError("Unable to Load file");
                }
                setSaveProgress(false);
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
    }

    @Override
    public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
            mTimer = null;
        }
        mTimer = new Timer();
        mTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                mModified = !mInput.getText().toString().equals(mOriginal);
                invalidateOptionsMenu();
            }
        }, 250);
    }

    @Override
    public void afterTextChanged(Editable editable) {
    }

    private class LoadContent extends AsyncTask<Void, Void, StringBuilder>{

        private Uri uri;
        private String errorMsg;

        LoadContent(Uri uri){
            this.uri = uri;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            setProgress(true);
        }

        @Override
        protected StringBuilder doInBackground(Void... params) {
            InputStream is = getInputStream(uri);
            if(null == is){
                errorMsg = "Unable to Load file";
                return null;
            }
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                final StringBuilder text = new StringBuilder();
                try {
                    while ((line = br.readLine()) != null) {
                        text.append(line);
                        text.append('\n');
                    }
                } catch (final OutOfMemoryError e) {
                    e.printStackTrace();
                    errorMsg = e.getLocalizedMessage();
                }
                br.close();
                return text;
            }catch (Exception e){
                e.printStackTrace();
                errorMsg = e.getLocalizedMessage();
            }finally {
                if(null != is){
                    try {
                        is.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(StringBuilder result) {
            super.onPostExecute(result);
            if(!Utils.isActivityAlive(NoteActivity.this)) {
                return;
            }
            setProgress(false);
            if(null == result){
                showError(errorMsg);
                return;
            }
            try {
                mOriginal = result.toString();
                result.setLength(0); // clear string builder to reduce memory usage
                mInput.setText(mOriginal);
            } catch (OutOfMemoryError e) {
                showError(e.getLocalizedMessage());
            }
        }
    }

    private class SaveContent extends AsyncTask<Void, Void, Void>{

        private Uri uri;
        private String errorMsg;
        private boolean exitAfter;

        SaveContent(Uri uri, boolean exitAfter){
            this.uri = uri;
            this.exitAfter = exitAfter;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            setSaveProgress(true);
        }

        @Override
        protected Void doInBackground(Void... params) {
            String authority = uri.getAuthority();
            if(authority.equalsIgnoreCase(RootedStorageProvider.AUTHORITY)){
                InputStream is = RootCommands.putFile(getPath(uri), mInput.getText().toString());
                if(null == is){
                    errorMsg = "Unable to save file";
                }
                return null;
            }
            OutputStream os = getOutStream(uri);
            if(null == os){
                errorMsg = "Unable to save file";
                return null;
            }
            try {
                os.write(mInput.getText().toString().getBytes("UTF-8"));
                os.close();
            } catch (IOException e) {
                e.printStackTrace();
                errorMsg = e.getLocalizedMessage();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            if(!Utils.isActivityAlive(NoteActivity.this)) {
                return;
            }
            setSaveProgress(false);
            if(!TextUtils.isEmpty(errorMsg)){
                showError(errorMsg);
                return;
            }
            if (exitAfter) {
                mOriginal = null;
                finish();
            } else {
                mOriginal = mInput.getText().toString();
                mModified = false;
                invalidateOptionsMenu();
            }
        }
    }

    private void setProgress(boolean show) {
        mInput.setVisibility(show ? View.GONE : View.VISIBLE);
        findViewById(R.id.progress).setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setSaveProgress(boolean show) {
        mInput.setEnabled(!show);
        findViewById(R.id.progress).setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private InputStream getInputStream(Uri uri){
        String scheme = uri.getScheme();
        String authority = uri.getAuthority();
        if(authority.equalsIgnoreCase(RootedStorageProvider.AUTHORITY)){
            return RootCommands.getFile(getPath(uri));
        }
        if (scheme.startsWith(ContentResolver.SCHEME_CONTENT)) {
            try {
                return getContentResolver().openInputStream(uri);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (scheme.startsWith(ContentResolver.SCHEME_FILE)) {
            File f = new File(uri.getPath());
            if (f.exists()) {
                try {
                    return new FileInputStream(f);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    private OutputStream getOutStream(Uri uri){
        String scheme = uri.getScheme();
        if (scheme.startsWith(ContentResolver.SCHEME_CONTENT)) {
            try {
                return getContentResolver().openOutputStream(uri);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (scheme.startsWith(ContentResolver.SCHEME_FILE)) {
            File f = new File(uri.getPath());
            if (f.exists()) {
                try {
                    return new FileOutputStream(f);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    private void getName(){
        Uri uri = getIntent().getData();
        if(null == uri){
            return;
        }
        String name = "";
        String scheme = uri.getScheme();
        if (scheme.startsWith(ContentResolver.SCHEME_CONTENT)) {
            String part = uri.getSchemeSpecificPart();
            final int splitIndex = part.indexOf(':', 1);
            if(splitIndex != -1) {
                name = part.substring(splitIndex + 1);
            }
            if(TextUtils.isEmpty(name)){
                name = uri.getLastPathSegment();
            }
        }
        else if (uri.getScheme().startsWith(ContentResolver.SCHEME_FILE)) {
            name = uri.getLastPathSegment();
        }
        getSupportActionBar().setTitle(FileUtils.getName(name));
        getSupportActionBar().setSubtitle("");
    }

    private  String getPath(Uri uri){
        String path = uri.getSchemeSpecificPart();
        String part = uri.getSchemeSpecificPart();
        final int splitIndex = part.indexOf(':', 1);
        if(splitIndex != -1) {
            path = part.substring(splitIndex + 1);
        }
        return path;
    }

    public void showError(String msg){
        showToast(msg, SnackBar.Style.ALERT, SnackBar.SHORT_SNACK);
    }

    public void showToast(String msg, SnackBar.Style style, short duration){
        new SnackBar.Builder(this)
                .withMessage(msg)
                .withStyle(style)
                .withActionMessageId(android.R.string.ok)
                .withDuration(duration)
                .show();
    }

}