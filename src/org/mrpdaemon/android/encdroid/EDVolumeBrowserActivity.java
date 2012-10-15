/*
 * encdroid - EncFS client application for Android
 * Copyright (C) 2012  Mark R. Pariente
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mrpdaemon.android.encdroid;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

import org.mrpdaemon.sec.encfs.EncFSFile;
import org.mrpdaemon.sec.encfs.EncFSFileInputStream;
import org.mrpdaemon.sec.encfs.EncFSFileOutputStream;
import org.mrpdaemon.sec.encfs.EncFSVolume;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnShowListener;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.text.Editable;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class EDVolumeBrowserActivity extends ListActivity {

	// Parameter key for specifying volume index
	public final static String VOL_ID_KEY = "vol_id";

	// Name of the SD card directory for copying files into
	public final static String ENCDROID_SD_DIR_NAME = "Encdroid";

	// Request ID's for calling into different activities
	public final static int VIEW_FILE_REQUEST = 0;
	public final static int PICK_FILE_REQUEST = 1;
	public final static int EXPORT_FILE_REQUEST = 2;

	// Dialog ID's
	private final static int DIALOG_ERROR = 0;
	private final static int DIALOG_FILE_RENAME = 1;
	private final static int DIALOG_FILE_DELETE = 2;
	private final static int DIALOG_CREATE_FOLDER = 3;

	// Async task ID's
	private final static int ASYNC_TASK_SYNC = 0;
	private final static int ASYNC_TASK_IMPORT = 1;
	private final static int ASYNC_TASK_DECRYPT = 2;
	private final static int ASYNC_TASK_EXPORT = 3;
	private final static int ASYNC_TASK_RENAME = 4;
	private final static int ASYNC_TASK_DELETE = 5;
	private final static int ASYNC_TASK_CREATE_DIR = 6;

	// Logger tag
	private final static String TAG = "EDVolumeBrowserActivity";

	// Adapter for the list
	private EDFileChooserAdapter mAdapter = null;

	// List that is currently being displayed
	private List<EDFileChooserItem> mCurFileList;

	// EDVolume
	private EDVolume mEDVolume;

	// EncFS volume
	private EncFSVolume mVolume;

	// Directory stack
	private Stack<EncFSFile> mDirStack;

	// Current directory
	private EncFSFile mCurEncFSDir;

	// Application object
	private EDApplication mApp;

	// Text for the error dialog
	private String mErrDialogText = "";

	// Progress dialog for async progress
	private ProgressDialog mProgDialog = null;

	// Async task object
	private AsyncTask<Void, Void, Boolean> mAsyncTask = null;

	// File observer
	private EDFileObserver mFileObserver;

	// EncFSFile that is currently opened
	private EncFSFile mOpenFile;

	// File that is currently selected
	private EDFileChooserItem mSelectedFile;

	// EncFSFile that is being pasted
	private EncFSFile mPasteFile = null;

	// Paste operations
	private static final int PASTE_OP_NONE = 0;
	private static final int PASTE_OP_CUT = 1;
	private static final int PASTE_OP_COPY = 2;

	// Paste mode
	private int mPasteMode = PASTE_OP_NONE;

	// Broadcast receiver to monitor external storage state
	BroadcastReceiver mExternalStorageReceiver;

	// Whether external storage is available
	boolean mExternalStorageAvailable = false;

	// Whether external storage is writable
	boolean mExternalStorageWriteable = false;

	// Action bar wrapper
	private EDActionBar mActionBar = null;

	// Text view for list header
	private TextView mListHeader = null;

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onCreate(android.os.Bundle)
	 */
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.file_chooser);

		Bundle params = getIntent().getExtras();

		mApp = (EDApplication) getApplication();

		mCurFileList = new ArrayList<EDFileChooserItem>();

		// Start monitoring external storage state
		mExternalStorageReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				updateExternalStorageState();
			}
		};
		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
		filter.addAction(Intent.ACTION_MEDIA_REMOVED);
		registerReceiver(mExternalStorageReceiver, filter);
		updateExternalStorageState();

		if (mExternalStorageAvailable == false) {
			Log.e(TAG, "No SD card is available");
			mErrDialogText = getString(R.string.error_no_sd_card);
			showDialog(DIALOG_ERROR);
			finish();
		}

		int position = params.getInt(VOL_ID_KEY);
		mEDVolume = mApp.getVolumeList().get(position);
		mVolume = mEDVolume.getVolume();
		mDirStack = new Stack<EncFSFile>();
		mCurEncFSDir = mVolume.getRootDir();

		launchFillTask();

		registerForContextMenu(this.getListView());

		if (mApp.isActionBarAvailable()) {
			mActionBar = new EDActionBar(this);
			mActionBar.setDisplayHomeAsUpEnabled(true);
		}

		mListHeader = new TextView(this);
		mListHeader.setTypeface(null, Typeface.BOLD);
		mListHeader.setTextSize(16);
		this.getListView().addHeaderView(mListHeader);
	}

	// Update the external storage state
	void updateExternalStorageState() {
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state)) {
			mExternalStorageAvailable = mExternalStorageWriteable = true;
		} else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
			mExternalStorageAvailable = true;
			mExternalStorageWriteable = false;
		} else {
			mExternalStorageAvailable = mExternalStorageWriteable = false;
		}
	}

	// Clean stuff up
	@Override
	protected void onDestroy() {
		super.onDestroy();
		unregisterReceiver(mExternalStorageReceiver);
	}

	// Create the options menu
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.volume_browser_menu, menu);
		return super.onCreateOptionsMenu(menu);
	}

	// Modify options menu items
	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem pasteItem = menu.findItem(R.id.volume_browser_menu_paste);
		if (mPasteMode != PASTE_OP_NONE) {
			pasteItem.setVisible(true);
		} else {
			pasteItem.setVisible(false);
		}
		return super.onPrepareOptionsMenu(menu);
	}

	// Handler for options menu selections
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.volume_browser_menu_import:
			Intent startFileChooser = new Intent(this,
					EDFileChooserActivity.class);

			Bundle fileChooserParams = new Bundle();
			fileChooserParams.putInt(EDFileChooserActivity.MODE_KEY,
					EDFileChooserActivity.FILE_PICKER_MODE);
			startFileChooser.putExtras(fileChooserParams);

			startActivityForResult(startFileChooser, PICK_FILE_REQUEST);
			return true;
		case R.id.volume_browser_menu_mkdir:
			showDialog(DIALOG_CREATE_FOLDER);
			return true;
		case R.id.volume_browser_menu_paste:
			// Show progress dialog
			mProgDialog = new ProgressDialog(EDVolumeBrowserActivity.this);
			if (mPasteMode == PASTE_OP_COPY) {
				mProgDialog.setTitle(getString(R.string.copy_dialog_title_str));
			} else {
				mProgDialog.setTitle(getString(R.string.cut_dialog_title_str));
			}
			mProgDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			mProgDialog.setCancelable(false);
			mProgDialog.show();

			// Launch async task to paste file
			mAsyncTask = new EDPasteFileTask(mProgDialog);
			mAsyncTask.execute();

			return true;
		case R.id.volume_browser_menu_refresh:
			launchFillTask();
			return true;
		case android.R.id.home:
			if (mCurEncFSDir == mVolume.getRootDir()) {
				// Go back to volume list
				Intent intent = new Intent(this, EDVolumeListActivity.class);
				intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
				startActivity(intent);
			} else {
				mCurEncFSDir = mDirStack.pop();
				launchFillTask();
			}
			return true;
		default:
			return false;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onKeyDown(int, android.view.KeyEvent)
	 */
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			if (mCurEncFSDir == mVolume.getRootDir()) {
				// Go back to volume list
				Intent intent = new Intent(this, EDVolumeListActivity.class);
				intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
				startActivity(intent);
			} else {
				mCurEncFSDir = mDirStack.pop();
				launchFillTask();
			}
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onPrepareDialog(int, android.app.Dialog)
	 */
	@Override
	protected void onPrepareDialog(int id, final Dialog dialog) {
		EditText input;
		AlertDialog ad = (AlertDialog) dialog;

		switch (id) {
		case DIALOG_FILE_DELETE:
			ad.setTitle(String.format(getString(R.string.del_dialog_title_str),
					mSelectedFile.getName()));
			break;
		case DIALOG_FILE_RENAME:
		case DIALOG_CREATE_FOLDER:
			input = (EditText) dialog.findViewById(R.id.dialog_edit_text);
			if (input != null) {
				if (id == DIALOG_FILE_RENAME) {
					input.setText(mSelectedFile.getName());
				} else if (id == DIALOG_CREATE_FOLDER) {
					input.setText("");
				}
			} else {
				Log.e(TAG,
						"dialog.findViewById returned null for dialog_edit_text");
			}

			/*
			 * We want these dialogs to immediately proceed when the user taps
			 * "Done" in the keyboard, so we create an EditorActionListener to
			 * catch the DONE action and trigger the positive button's onClick()
			 * event.
			 */
			input.setImeOptions(EditorInfo.IME_ACTION_DONE);
			input.setOnEditorActionListener(new OnEditorActionListener() {
				@Override
				public boolean onEditorAction(TextView v, int actionId,
						KeyEvent event) {
					if (actionId == EditorInfo.IME_ACTION_DONE) {
						Button button = ((AlertDialog) dialog)
								.getButton(Dialog.BUTTON_POSITIVE);
						button.performClick();
						return true;
					}
					return false;
				}
			});

			break;
		case DIALOG_ERROR:
			ad.setMessage(mErrDialogText);
			break;
		default:
			break;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onCreateDialog(int)
	 */
	@Override
	protected Dialog onCreateDialog(int id) {
		AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
		AlertDialog alertDialog = null;

		LayoutInflater inflater = LayoutInflater.from(this);

		final EditText input;
		final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

		switch (id) {
		case DIALOG_FILE_RENAME: // Rename file dialog

			input = (EditText) inflater.inflate(R.layout.dialog_edit, null);

			alertBuilder.setTitle(getString(R.string.frename_dialog_title_str));
			alertBuilder.setView(input);
			alertBuilder.setPositiveButton(getString(R.string.btn_ok_str),
					new DialogInterface.OnClickListener() {
						// Rename the file
						public void onClick(DialogInterface dialog,
								int whichButton) {
							Editable value = input.getText();
							launchAsyncTask(ASYNC_TASK_RENAME, value.toString());
						}
					});
			// Cancel button
			alertBuilder.setNegativeButton(getString(R.string.btn_cancel_str),
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog,
								int whichButton) {
							dialog.cancel();
						}
					});
			alertDialog = alertBuilder.create();

			// Show keyboard
			alertDialog.setOnShowListener(new OnShowListener() {

				@Override
				public void onShow(DialogInterface dialog) {
					imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
				}
			});
			break;
		case DIALOG_CREATE_FOLDER: // Create folder dialog

			input = (EditText) inflater.inflate(R.layout.dialog_edit, null);

			alertBuilder.setTitle(getString(R.string.mkdir_dialog_input_str));
			alertBuilder.setView(input);
			alertBuilder.setPositiveButton(getString(R.string.btn_ok_str),
					new DialogInterface.OnClickListener() {
						// Create the folder
						public void onClick(DialogInterface dialog,
								int whichButton) {
							Editable value = input.getText();
							launchAsyncTask(ASYNC_TASK_CREATE_DIR,
									value.toString());
						}
					});
			// Cancel button
			alertBuilder.setNegativeButton(getString(R.string.btn_cancel_str),
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog,
								int whichButton) {
							dialog.cancel();
						}
					});
			alertDialog = alertBuilder.create();

			// Show keyboard
			alertDialog.setOnShowListener(new OnShowListener() {

				@Override
				public void onShow(DialogInterface dialog) {
					imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
				}
			});
			break;
		case DIALOG_FILE_DELETE:
			alertBuilder.setTitle(String.format(
					getString(R.string.del_dialog_title_str),
					mSelectedFile.getName()));
			alertBuilder.setCancelable(false);
			alertBuilder.setPositiveButton(getString(R.string.btn_yes_str),
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							// Delete the file
							launchAsyncTask(ASYNC_TASK_DELETE, null);
						}
					});
			alertBuilder.setNegativeButton(getString(R.string.btn_no_str),
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							dialog.cancel();
						}
					});
			alertDialog = alertBuilder.create();
			break;
		case DIALOG_ERROR:
			alertBuilder.setMessage(mErrDialogText);
			alertBuilder.setCancelable(false);
			alertBuilder.setNeutralButton(getString(R.string.btn_ok_str),
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog,
								int whichButton) {
							dialog.dismiss();
						}
					});
			alertDialog = alertBuilder.create();
			break;

		default:
			Log.e(TAG, "Unknown dialog ID requested " + id);
			return null;
		}

		return alertDialog;
	}

	private void fill() {

		EncFSFile[] childEncFSFiles = null;

		try {
			childEncFSFiles = mCurEncFSDir.listFiles();
		} catch (IOException e) {
			Log.e(TAG, e.getMessage());
			mErrDialogText = "Unable to list files: " + e.getMessage();
			showDialog(DIALOG_ERROR);
			return;
		}

		final boolean emptyDir = childEncFSFiles.length == 0 ? true : false;

		// Set title from UI thread
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				setTitle(mEDVolume.getName());

				if ((emptyDir == true)
						&& (mCurEncFSDir == mVolume.getRootDir())) {
					// Empty volume message
					mListHeader.setText(getString(R.string.no_files));
				} else {
					mListHeader.setText(mCurEncFSDir.getPath());
				}
			}
		});

		List<EDFileChooserItem> directories = new ArrayList<EDFileChooserItem>();
		List<EDFileChooserItem> files = new ArrayList<EDFileChooserItem>();

		for (EncFSFile file : childEncFSFiles) {
			if (file.isDirectory()) {
				directories.add(new EDFileChooserItem(file.getName(), true,
						file, 0));
			} else {
				if (!file.getName().equals(EncFSVolume.CONFIG_FILE_NAME)) {
					files.add(new EDFileChooserItem(file.getName(), false,
							file, file.getLength()));
				}
			}
		}

		// Sort directories and files separately
		Collections.sort(directories);
		Collections.sort(files);

		// Merge directories + files into current file list
		mCurFileList.clear();
		mCurFileList.addAll(directories);
		mCurFileList.addAll(files);

		/*
		 * Add an item for the parent directory (..) in case where no ActionBar
		 * is present (API < 11). With ActionBar we use the Up icon for
		 * navigation.
		 */
		if ((mActionBar == null) && (mCurEncFSDir != mVolume.getRootDir())) {
			mCurFileList.add(0, new EDFileChooserItem("..", true, "", 0));
		}

		if (mAdapter == null) {
			mAdapter = new EDFileChooserAdapter(this,
					R.layout.file_chooser_item, mCurFileList);

			// Set list adapter from UI thread
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					setListAdapter(mAdapter);
				}
			});
		} else {
			// Notify data set change from UI thread
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					mAdapter.notifyDataSetChanged();
				}
			});
		}
	}

	// Show a progress spinner and launch the fill task
	private void launchFillTask() {
		mProgDialog = new ProgressDialog(EDVolumeBrowserActivity.this);
		mProgDialog.setTitle(getString(R.string.loading_contents));
		mProgDialog.setCancelable(false);
		mProgDialog.show();
		new EDVolumeBrowserFillTask(mProgDialog).execute();
	}

	/*
	 * Task to fill the volume browser list. This is needed because fill() can
	 * end up doing network I/O with certain file providers and starting with
	 * API version 13 doing so results in a NetworkOnMainThreadException.
	 */
	private class EDVolumeBrowserFillTask extends AsyncTask<Void, Void, Void> {

		private ProgressDialog myDialog;

		public EDVolumeBrowserFillTask(ProgressDialog dialog) {
			super();
			myDialog = dialog;
		}

		@Override
		protected Void doInBackground(Void... arg0) {
			fill();
			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			super.onPostExecute(result);

			if (myDialog.isShowing()) {
				myDialog.dismiss();
			}
		}
	}

	private void launchAsyncTask(int taskId, File fileArg, EncFSFile encFSArg) {
		// Show progress dialog
		mProgDialog = new ProgressDialog(EDVolumeBrowserActivity.this);
		switch (taskId) {
		case ASYNC_TASK_SYNC:
			mProgDialog.setTitle(String.format(
					getString(R.string.encrypt_dialog_title_str),
					mOpenFile.getName()));
			break;
		case ASYNC_TASK_IMPORT:
			mProgDialog.setTitle(String.format(
					getString(R.string.import_dialog_title_str),
					fileArg.getName()));
			break;
		case ASYNC_TASK_DECRYPT:
			mProgDialog.setTitle(String.format(
					getString(R.string.decrypt_dialog_title_str),
					mOpenFile.getName()));
			break;
		case ASYNC_TASK_EXPORT:
			mProgDialog.setTitle(String.format(
					getString(R.string.export_dialog_title_str),
					mOpenFile.getName()));
			break;
		default:
			Log.e(TAG, "Unknown task ID: " + taskId);
			break;
		}
		mProgDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		mProgDialog.setCancelable(false);
		mProgDialog.show();

		// Launch async task
		switch (taskId) {
		case ASYNC_TASK_DECRYPT:
			mAsyncTask = new EDViewFileTask(mProgDialog, encFSArg, fileArg);
			break;
		case ASYNC_TASK_IMPORT:
			mAsyncTask = new EDImportFileTask(mProgDialog, fileArg);
			break;
		case ASYNC_TASK_SYNC:
			mAsyncTask = new EDSyncFileTask(mProgDialog, fileArg, encFSArg);
			break;
		case ASYNC_TASK_EXPORT:
			mAsyncTask = new EDExportFileTask(mProgDialog, encFSArg, fileArg);
			break;
		default:
			Log.e(TAG, "Unknown task ID: " + taskId);
			break;
		}
		mAsyncTask.execute();
	}

	private void launchAsyncTask(int taskId, String strArg) {
		// Show progress dialog
		mProgDialog = new ProgressDialog(EDVolumeBrowserActivity.this);
		switch (taskId) {
		case ASYNC_TASK_RENAME:
			mProgDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			mProgDialog.setTitle(String.format(
					getString(R.string.rename_dialog_title_str),
					mSelectedFile.getName(), strArg));
			break;
		case ASYNC_TASK_DELETE:
			mProgDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			mProgDialog.setTitle(String.format(
					getString(R.string.delete_dialog_title_str),
					mSelectedFile.getName()));
			break;
		case ASYNC_TASK_CREATE_DIR:
			mProgDialog.setTitle(String.format(
					getString(R.string.mkdir_dialog_title_str), strArg));
			break;
		default:
			Log.e(TAG, "Unknown task ID: " + taskId);
			break;
		}

		mProgDialog.setCancelable(false);
		mProgDialog.show();

		// Launch async task
		switch (taskId) {
		case ASYNC_TASK_RENAME:
			mAsyncTask = new EDMetadataOpTask(mProgDialog,
					EDMetadataOpTask.RENAME_FILE, strArg);
			break;
		case ASYNC_TASK_DELETE:
			mAsyncTask = new EDMetadataOpTask(mProgDialog,
					EDMetadataOpTask.DELETE_FILE, strArg);
			break;
		case ASYNC_TASK_CREATE_DIR:
			mAsyncTask = new EDMetadataOpTask(mProgDialog,
					EDMetadataOpTask.CREATE_DIR, strArg);
			break;
		default:
			Log.e(TAG, "Unknown task ID: " + taskId);
			break;
		}
		mAsyncTask.execute();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.ListActivity#onListItemClick(android.widget.ListView,
	 * android.view.View, int, long)
	 */
	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {
		super.onListItemClick(l, v, position, id);

		// We use position - 1 since we have an extra header
		if (position == 0) {
			return;
		}
		EDFileChooserItem selected = mAdapter.getItem(position - 1);

		if (selected.isDirectory()) {
			if (selected.getName().equals("..")) {
				// Chdir up
				mCurEncFSDir = mDirStack.pop();
			} else {
				mDirStack.push(mCurEncFSDir);
				mCurEncFSDir = selected.getFile();
			}

			launchFillTask();
		} else {
			// Launch file in external application

			if (mExternalStorageWriteable == false) {
				mErrDialogText = getString(R.string.error_sd_readonly);
				showDialog(DIALOG_ERROR);
				return;
			}

			// Create sdcard dir if it doesn't exist
			File encDroidDir = new File(
					Environment.getExternalStorageDirectory(),
					ENCDROID_SD_DIR_NAME);
			if (!encDroidDir.exists()) {
				encDroidDir.mkdir();
			}

			mOpenFile = selected.getFile();
			File dstFile = new File(encDroidDir, mOpenFile.getName());

			// Launch async task to decrypt the file
			launchAsyncTask(ASYNC_TASK_DECRYPT, dstFile, mOpenFile);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onContextItemSelected(android.view.MenuItem)
	 */
	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AdapterContextMenuInfo info = (AdapterContextMenuInfo) item
				.getMenuInfo();
		switch (item.getItemId()) {
		case R.id.volume_browser_menu_rename:
			mSelectedFile = mAdapter.getItem((int) info.id);
			showDialog(DIALOG_FILE_RENAME);
			return true;
		case R.id.volume_browser_menu_delete:
			mSelectedFile = mAdapter.getItem((int) info.id);
			showDialog(DIALOG_FILE_DELETE);
			return true;
		case R.id.volume_browser_menu_cut:
			mSelectedFile = mAdapter.getItem((int) info.id);
			mPasteFile = mSelectedFile.getFile();
			mPasteMode = PASTE_OP_CUT;

			if (mApp.isActionBarAvailable()) {
				mActionBar.invalidateOptionsMenu(this);
			}

			// Show toast
			Toast.makeText(
					getApplicationContext(),
					String.format(getString(R.string.toast_cut_file),
							mPasteFile.getName()), Toast.LENGTH_SHORT).show();

			return true;
		case R.id.volume_browser_menu_copy:
			mSelectedFile = mAdapter.getItem((int) info.id);
			mPasteFile = mSelectedFile.getFile();
			mPasteMode = PASTE_OP_COPY;

			if (mApp.isActionBarAvailable()) {
				mActionBar.invalidateOptionsMenu(this);
			}

			// Show toast
			Toast.makeText(
					getApplicationContext(),
					String.format(getString(R.string.toast_copy_file),
							mPasteFile.getName()), Toast.LENGTH_SHORT).show();

			return true;
		case R.id.volume_browser_menu_export:
			mSelectedFile = mAdapter.getItem((int) info.id);
			mOpenFile = mSelectedFile.getFile();

			Intent startFileExport = new Intent(this,
					EDFileChooserActivity.class);

			Bundle exportFileParams = new Bundle();
			exportFileParams.putInt(EDFileChooserActivity.MODE_KEY,
					EDFileChooserActivity.EXPORT_FILE_MODE);
			exportFileParams.putString(EDFileChooserActivity.EXPORT_FILE_KEY,
					mSelectedFile.getName());
			startFileExport.putExtras(exportFileParams);

			startActivityForResult(startFileExport, EXPORT_FILE_REQUEST);
			return true;
		default:
			return super.onContextItemSelected(item);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onCreateContextMenu(android.view.ContextMenu,
	 * android.view.View, android.view.ContextMenu.ContextMenuInfo)
	 */
	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.volume_browser_context, menu);
	}

	private boolean copyStreams(InputStream is, OutputStream os,
			ProgressDialog dialog) {
		try {
			byte[] buf = new byte[8192];
			int bytesRead = 0;
			try {
				try {
					bytesRead = is.read(buf);
					while (bytesRead >= 0) {
						os.write(buf, 0, bytesRead);
						bytesRead = is.read(buf);
						if (dialog != null) {
							dialog.incrementProgressBy(8192);
						}
					}
				} finally {
					is.close();
				}
			} finally {
				os.close();
			}
		} catch (IOException e) {
			Log.e(TAG, e.getMessage());
			mErrDialogText = e.getMessage();
			return false;
		}

		return true;
	}

	private boolean exportFile(EncFSFile srcFile, File dstFile,
			ProgressDialog dialog) {
		EncFSFileInputStream efis = null;
		try {
			efis = new EncFSFileInputStream(srcFile);
		} catch (Exception e) {
			Log.e(TAG, e.getMessage());
			mErrDialogText = e.getMessage();
			return false;
		}

		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(dstFile);
		} catch (FileNotFoundException e) {
			Log.e(TAG, e.getMessage());
			mErrDialogText = e.getMessage();
			try {
				efis.close();
			} catch (IOException ioe) {
				mErrDialogText += ioe.getMessage();
			}
			return false;
		}

		return copyStreams(efis, fos, dialog);
	}

	// Export all files/dirs under the EncFS dir to the given dir
	private boolean recursiveExport(EncFSFile srcDir, File dstDir,
			ProgressDialog dialog) {
		try {
			for (EncFSFile file : srcDir.listFiles()) {

				File dstFile = new File(dstDir, file.getName());

				if (file.isDirectory()) { // Directory
					if (dstFile.mkdir()) {
						dialog.incrementProgressBy(1);
						// Export all files/folders under this dir
						if (recursiveExport(file, dstFile, dialog) == false) {
							return false;
						}
					} else {
						mErrDialogText = String.format(
								getString(R.string.error_mkdir_fail),
								dstFile.getAbsolutePath());
						return false;
					}
				} else { // Export an individual file
					if (exportFile(file, dstFile, null) == false) {
						return false;
					}
					dialog.incrementProgressBy(1);
				}
			}
		} catch (Exception e) {
			Log.e(TAG, e.getMessage());
			mErrDialogText = e.getMessage();
			return false;
		}
		return true;
	}

	private class EDExportFileTask extends AsyncTask<Void, Void, Boolean> {

		// The progress dialog for this task
		private ProgressDialog myDialog;

		// Source file
		private EncFSFile srcFile;

		// Destination file
		private File dstFile;

		public EDExportFileTask(ProgressDialog dialog, EncFSFile srcFile,
				File dstFile) {
			super();
			this.myDialog = dialog;
			this.srcFile = srcFile;
			this.dstFile = dstFile;
		}

		@Override
		protected Boolean doInBackground(Void... args) {
			if (srcFile.isDirectory()) {
				myDialog.setMax(EncFSVolume.countFiles(srcFile));

				// Create destination dir
				if (dstFile.mkdir()) {
					myDialog.incrementProgressBy(1);
				} else {
					mErrDialogText = String.format(
							getString(R.string.error_mkdir_fail),
							dstFile.getAbsolutePath());
					return false;
				}

				return recursiveExport(srcFile, dstFile, myDialog);
			} else {
				// Use size of the file
				myDialog.setMax((int) srcFile.getLength());
				return exportFile(srcFile, dstFile, myDialog);
			}

		}

		// Run after the task is complete
		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);

			if (myDialog.isShowing()) {
				myDialog.dismiss();
			}

			if (!isCancelled()) {
				if (result == true) {
					// Show toast
					Toast.makeText(getApplicationContext(),
							getString(R.string.toast_files_exported),
							Toast.LENGTH_SHORT).show();
				} else {
					showDialog(DIALOG_ERROR);
				}
			}
		}
	}

	private class EDViewFileTask extends AsyncTask<Void, Void, Boolean> {

		// The progress dialog for this task
		private ProgressDialog myDialog;

		// Source file
		private EncFSFile srcFile;

		// Destination file
		private File dstFile;

		public EDViewFileTask(ProgressDialog dialog, EncFSFile srcFile,
				File dstFile) {
			super();
			this.myDialog = dialog;
			this.srcFile = srcFile;
			this.dstFile = dstFile;

			// Set dialog max in KB
			myDialog.setMax((int) srcFile.getLength());
		}

		@Override
		protected Boolean doInBackground(Void... args) {
			return exportFile(srcFile, dstFile, myDialog);
		}

		// Run after the task is complete
		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);

			if (myDialog.isShowing()) {
				myDialog.dismiss();
			}

			if (!isCancelled()) {
				if (result == true) {
					// Set up a file observer
					mFileObserver = new EDFileObserver(
							dstFile.getAbsolutePath());
					mFileObserver.startWatching();

					// Figure out the MIME type
					String extension = MimeTypeMap
							.getFileExtensionFromUrl(dstFile.getName());
					String mimeType = MimeTypeMap.getSingleton()
							.getMimeTypeFromExtension(extension);

					// Launch viewer app
					Intent openIntent = new Intent(Intent.ACTION_VIEW);

					if (mimeType == null) {
						openIntent.setDataAndType(Uri.fromFile(dstFile),
								"application/unknown");
					} else {
						openIntent.setDataAndType(Uri.fromFile(dstFile),
								mimeType);
					}

					try {
						startActivityForResult(openIntent, VIEW_FILE_REQUEST);
					} catch (ActivityNotFoundException e) {
						mErrDialogText = String.format(
								getString(R.string.error_no_viewer_app),
								srcFile.getPath());
						Log.e(TAG, mErrDialogText);
						showDialog(DIALOG_ERROR);
					}
				} else {
					showDialog(DIALOG_ERROR);
				}
			}
		}
	}

	private boolean importFile(File srcFile, EncFSFile dstFile,
			ProgressDialog dialog) {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(srcFile);
		} catch (FileNotFoundException e) {
			Log.e(TAG, e.getMessage());
			mErrDialogText = e.getMessage();
			return false;
		}

		EncFSFileOutputStream efos = null;
		try {
			efos = new EncFSFileOutputStream(dstFile, srcFile.length());
		} catch (Exception e) {
			Log.e(TAG, e.getMessage());
			mErrDialogText = e.getMessage();
			try {
				fis.close();
			} catch (IOException ioe) {
				mErrDialogText += ioe.getMessage();
			}
			return false;
		}

		return copyStreams(fis, efos, dialog);
	}

	private class EDSyncFileTask extends AsyncTask<Void, Void, Boolean> {

		// The progress dialog for this task
		private ProgressDialog myDialog;

		// Source file
		private File srcFile;

		// Destination file
		private EncFSFile dstFile;

		public EDSyncFileTask(ProgressDialog dialog, File srcFile,
				EncFSFile dstFile) {
			super();
			this.myDialog = dialog;
			this.srcFile = srcFile;
			this.dstFile = dstFile;

			// Set dialog max in KB
			myDialog.setMax((int) srcFile.length());
		}

		@Override
		protected Boolean doInBackground(Void... args) {
			return importFile(srcFile, dstFile, myDialog);
		}

		// Run after the task is complete
		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);

			if (myDialog.isShowing()) {
				myDialog.dismiss();
			}

			if (!isCancelled()) {
				if (result == true) {
					// Delete the file
					if (mFileObserver.wasModified()) {
						Log.d(TAG, "Deleting '" + srcFile.getAbsolutePath()
								+ "' : file was sync'd.");
					} else {
						Log.d(TAG, "Deleting '" + srcFile.getAbsolutePath()
								+ "' : file not modified.");
					}
					srcFile.delete();

					// Clean up reference to the file observer
					mFileObserver = null;

					// Show toast
					Toast.makeText(
							getApplicationContext(),
							String.format(
									getString(R.string.toast_encrypt_file),
									mOpenFile.getName()), Toast.LENGTH_SHORT)
							.show();

					// Refresh view to get byte size changes
					launchFillTask();
				} else {
					showDialog(DIALOG_ERROR);
				}
			}
		}
	}

	// Count files and directories under the given file
	private int countFiles(File file) {
		if (file.isDirectory()) {
			int dirCount = 1;
			for (File subFile : file.listFiles()) {
				dirCount += countFiles(subFile);
			}
			return dirCount;
		} else {
			return 1;
		}
	}

	// Import all files/dirs under the given file to the given EncFS dir
	private boolean recursiveImport(File srcDir, EncFSFile dstDir,
			ProgressDialog dialog) {
		for (File file : srcDir.listFiles()) {

			String dstPath = EncFSVolume.combinePath(dstDir, file.getName());

			try {
				if (file.isDirectory()) { // Directory
					if (mVolume.makeDir(dstPath)) {
						dialog.incrementProgressBy(1);
						// Import all files/folders under this dir
						if (recursiveImport(file, mVolume.getFile(dstPath),
								dialog) == false) {
							return false;
						}
					} else {
						mErrDialogText = String.format(
								getString(R.string.error_mkdir_fail), dstPath);
						return false;
					}
				} else { // Import an individual file
					if (importFile(file, mVolume.createFile(dstPath), null) == false) {
						return false;
					}
					dialog.incrementProgressBy(1);
				}
			} catch (Exception e) {
				Log.e(TAG, e.getMessage());
				mErrDialogText = e.getMessage();
				return false;
			}
		}
		return true;
	}

	private class EDImportFileTask extends AsyncTask<Void, Void, Boolean> {

		// The progress dialog for this task
		private ProgressDialog myDialog;

		// Source file
		private File srcFile;

		// Destination file
		private EncFSFile dstFile;

		public EDImportFileTask(ProgressDialog dialog, File srcFile) {
			super();
			this.myDialog = dialog;
			this.srcFile = srcFile;
		}

		@Override
		protected Boolean doInBackground(Void... args) {

			// Create destination encFS file or directory
			try {
				String dstPath = EncFSVolume.combinePath(mCurEncFSDir,
						srcFile.getName());

				if (srcFile.isDirectory()) {
					if (mVolume.makeDir(dstPath)) {
						dstFile = mVolume.getFile(dstPath);
					} else {
						mErrDialogText = String.format(
								getString(R.string.error_mkdir_fail), dstPath);
						return false;
					}
				} else {
					dstFile = mVolume.createFile(dstPath);
				}
			} catch (Exception e) {
				Log.e(TAG, e.getMessage());
				mErrDialogText = e.getMessage();
				return false;
			}

			if (srcFile.isDirectory()) {
				myDialog.setMax(countFiles(srcFile));
				return recursiveImport(srcFile, dstFile, myDialog);
			} else {
				// Use size of the file
				myDialog.setMax((int) srcFile.length());
				return importFile(srcFile, dstFile, myDialog);
			}

		}

		// Run after the task is complete
		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);

			if (myDialog.isShowing()) {
				myDialog.dismiss();
			}

			if (!isCancelled()) {
				if (result == true) {
					// Show toast
					Toast.makeText(getApplicationContext(),
							getString(R.string.toast_files_imported),
							Toast.LENGTH_SHORT).show();
					launchFillTask();
				} else {
					showDialog(DIALOG_ERROR);
					launchFillTask();
				}
			}
		}
	}

	private class EDPasteFileTask extends AsyncTask<Void, Void, Boolean> {

		// The progress dialog for this task
		private ProgressDialog myDialog;

		public EDPasteFileTask(ProgressDialog dialog) {
			super();
			this.myDialog = dialog;
		}

		@Override
		protected Boolean doInBackground(Void... args) {

			try {
				boolean result;
				if (mPasteMode == PASTE_OP_CUT) {
					result = mVolume.movePath(mPasteFile.getPath(), EncFSVolume
							.combinePath(mCurEncFSDir, mPasteFile),
							new EDProgressListener(myDialog,
									EDVolumeBrowserActivity.this));
				} else {
					// If destination path exists, use a duplicate name
					String combinedPath = EncFSVolume.combinePath(mCurEncFSDir,
							mPasteFile);
					if (mVolume.pathExists(combinedPath)) {
						// Bump up a counter until path doesn't exist
						int counter = 0;
						do {
							counter++;
							combinedPath = EncFSVolume.combinePath(
									mCurEncFSDir, "(Copy " + counter + ") "
											+ mPasteFile.getName());
						} while (mVolume.pathExists(combinedPath));

						result = mVolume.copyPath(mPasteFile.getPath(),
								combinedPath, new EDProgressListener(myDialog,
										EDVolumeBrowserActivity.this));
					} else {
						result = mVolume
								.copyPath(mPasteFile.getPath(), mCurEncFSDir
										.getPath(), new EDProgressListener(
										myDialog, EDVolumeBrowserActivity.this));
					}
				}

				if (result == false) {
					if (mPasteMode == PASTE_OP_CUT) {
						mErrDialogText = String.format(
								getString(R.string.error_move_fail),
								mPasteFile.getName(), mCurEncFSDir.getPath());
					} else {
						mErrDialogText = String.format(
								getString(R.string.error_copy_fail),
								mPasteFile.getName(), mCurEncFSDir.getPath());
					}

					return false;
				}
			} catch (Exception e) {
				if (e.getMessage() == null) {
					mErrDialogText = getString(R.string.paste_fail);
				} else {
					Log.e(TAG, e.getMessage());
					mErrDialogText = e.getMessage();
				}
				return false;
			}

			return true;
		}

		// Run after the task is complete
		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);

			if (myDialog.isShowing()) {
				myDialog.dismiss();
			}

			mPasteFile = null;
			mPasteMode = PASTE_OP_NONE;

			if (mApp.isActionBarAvailable()) {
				mActionBar.invalidateOptionsMenu(EDVolumeBrowserActivity.this);
			}

			if (!isCancelled()) {
				if (result == true) {
					launchFillTask();
				} else {
					showDialog(DIALOG_ERROR);
					launchFillTask();
				}
			}
		}
	}

	private class EDMetadataOpTask extends AsyncTask<Void, Void, Boolean> {

		// Valid modes for the task
		public static final int DELETE_FILE = 0;
		public static final int RENAME_FILE = 1;
		public static final int CREATE_DIR = 2;

		// The progress dialog for this task
		private ProgressDialog myDialog;

		// mode for the current task
		private int mode;

		// String argument for the task
		private String strArg;

		public EDMetadataOpTask(ProgressDialog dialog, int mode, String strArg) {
			super();
			this.myDialog = dialog;
			this.mode = mode;
			this.strArg = strArg;
		}

		@Override
		protected Boolean doInBackground(Void... args) {
			switch (mode) {
			case DELETE_FILE:
				try {
					// boolean result = mSelectedFile.getFile().delete();
					boolean result = mVolume.deletePath(mSelectedFile.getFile()
							.getPath(), true, new EDProgressListener(myDialog,
							EDVolumeBrowserActivity.this));

					if (result == false) {
						mErrDialogText = String.format(
								getString(R.string.error_delete_fail),
								mSelectedFile.getName());
						return false;
					}
				} catch (Exception e) {
					Log.e(TAG, e.getMessage());
					mErrDialogText = e.getMessage();
					return false;
				}
				return true;
			case RENAME_FILE:
				try {
					String dstPath = EncFSVolume.combinePath(mCurEncFSDir,
							strArg);

					// Check if the destination path exists
					if (mVolume.pathExists(dstPath)) {
						mErrDialogText = String.format(
								getString(R.string.error_path_exists), dstPath);
						return false;
					}

					boolean result = mVolume.movePath(EncFSVolume.combinePath(
							mCurEncFSDir, mSelectedFile.getName()), dstPath,
							new EDProgressListener(myDialog,
									EDVolumeBrowserActivity.this));

					if (result == false) {
						mErrDialogText = String.format(
								getString(R.string.error_rename_fail),
								mSelectedFile.getName(), strArg);
						return false;
					}
				} catch (Exception e) {
					Log.e(TAG, e.getMessage());
					mErrDialogText = e.getMessage();
					return false;
				}
				return true;
			case CREATE_DIR:
				try {
					boolean result = mVolume.makeDir(EncFSVolume.combinePath(
							mCurEncFSDir, strArg));

					if (result == false) {
						mErrDialogText = String.format(
								getString(R.string.error_mkdir_fail), strArg);
						return false;
					}
				} catch (Exception e) {
					Log.e(TAG, e.getMessage());
					mErrDialogText = e.getMessage();
					return false;
				}
				return true;
			default:
				return false;
			}
		}

		// Run after the task is complete
		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);

			if (myDialog.isShowing()) {
				myDialog.dismiss();
			}

			if (!isCancelled()) {
				if (result == true) {
					launchFillTask();
				} else {
					showDialog(DIALOG_ERROR);
					launchFillTask();
				}
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onActivityResult(int, int,
	 * android.content.Intent)
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		switch (requestCode) {
		case VIEW_FILE_REQUEST:
			// Don't need to watch any more
			mFileObserver.stopWatching();

			File dstFile = new File(mFileObserver.getPath());

			// If the file was modified we need to sync it back
			if (mFileObserver.wasModified()) {
				// Sync file contents
				try {
					launchAsyncTask(ASYNC_TASK_SYNC, dstFile, mOpenFile);
				} catch (Exception e) {
					mErrDialogText = e.getMessage();
					showDialog(DIALOG_ERROR);
				}
			} else {
				// File not modified, delete from SD
				dstFile.delete();
			}
			break;
		case PICK_FILE_REQUEST:
			if (resultCode == Activity.RESULT_OK) {
				String result = data.getExtras().getString(
						EDFileChooserActivity.RESULT_KEY);
				String importPath = new File(
						Environment.getExternalStorageDirectory(), result)
						.getAbsolutePath();
				Log.d(TAG, "Importing file: " + importPath);

				File importFile = new File(importPath);

				// Launch async task to complete importing
				launchAsyncTask(ASYNC_TASK_IMPORT, importFile, null);
			} else {
				Log.d(TAG, "File chooser returned unexpected return code: "
						+ resultCode);
			}
			break;
		case EXPORT_FILE_REQUEST:
			if (resultCode == Activity.RESULT_OK) {
				String result = data.getExtras().getString(
						EDFileChooserActivity.RESULT_KEY);
				String exportPath = new File(
						Environment.getExternalStorageDirectory(), result)
						.getAbsolutePath();
				Log.d(TAG, "Exporting file to: " + exportPath);

				if (mExternalStorageWriteable == false) {
					mErrDialogText = getString(R.string.error_sd_readonly);
					showDialog(DIALOG_ERROR);
					return;
				}

				File exportFile = new File(exportPath, mOpenFile.getName());

				if (exportFile.exists()) {
					// Error dialog
					mErrDialogText = String.format(
							getString(R.string.error_file_exists),
							exportFile.getName());
					showDialog(DIALOG_ERROR);
				} else {
					// Launch async task to export the file
					launchAsyncTask(ASYNC_TASK_EXPORT, exportFile, mOpenFile);
				}
			} else {
				Log.e(TAG, "File chooser returned unexpected return code: "
						+ resultCode);
			}
			break;
		default:
			Log.e(TAG, "Unknown request: " + requestCode);
			break;
		}
	}

	private class EDFileObserver extends FileObserver {

		private boolean modified;

		private String myPath;

		public EDFileObserver(String path) {
			super(path);
			myPath = path;
			modified = false;
		}

		public boolean wasModified() {
			return modified;
		}

		public String getPath() {
			return myPath;
		}

		@Override
		public void onEvent(int event, String path) {
			switch (event) {
			case CLOSE_WRITE:
				modified = true;
				break;
			default:
				break;
			}
		}
	}
}