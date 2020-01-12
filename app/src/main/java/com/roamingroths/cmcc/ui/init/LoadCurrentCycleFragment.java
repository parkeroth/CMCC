package com.roamingroths.cmcc.ui.init;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;

import com.google.firebase.auth.FirebaseUser;
import com.roamingroths.cmcc.application.MyApplication;
import com.roamingroths.cmcc.data.entities.Cycle;
import com.roamingroths.cmcc.data.repos.CycleRepo;
import com.roamingroths.cmcc.ui.entry.list.ChartEntryListActivity;
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog;

import org.joda.time.LocalDate;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import androidx.annotation.Nullable;
import io.reactivex.Maybe;
import io.reactivex.MaybeTransformer;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

/**
 * Created by parkeroth on 10/8/17.
 */

public class LoadCurrentCycleFragment extends SplashFragment implements UserInitializationListener {

  private static boolean DEBUG = false;
  private static String TAG = LoadCurrentCycleFragment.class.getSimpleName();

  private CompositeDisposable mDisposables = new CompositeDisposable();
  private CycleRepo mCycleRepo;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    mCycleRepo = new CycleRepo(MyApplication.cast(getActivity().getApplication()).db());
  }

  @Override
  public void onDestroy() {
    mDisposables.clear();
    super.onDestroy();
  }

  private MaybeTransformer<Cycle, Cycle> tryUseLatestAsCurrent() {
    return upstream -> upstream
        .switchIfEmpty(mCycleRepo.getLatestCycle()
        .observeOn(AndroidSchedulers.mainThread())
        .flatMap(latestCycle -> promptUseLatestAsCurrent()
            .observeOn(Schedulers.computation())
            .flatMapMaybe(useCurrent -> {
              if (!useCurrent) {
                return Maybe.empty();
              }
              Cycle copyOfLatest = new Cycle(latestCycle);
              copyOfLatest.endDate = null;
              return mCycleRepo.insertOrUpdate(copyOfLatest).andThen(Maybe.just(copyOfLatest));
            })));
  }

  private MaybeTransformer<Cycle, Cycle> initFirstCycle() {
    return upstream -> upstream
        .switchIfEmpty(promptForStart()
        .flatMap(startDate -> {
          Cycle cycle = new Cycle("foo", LocalDate.now(), null);
          return mCycleRepo.insertOrUpdate(cycle).andThen(Single.just(cycle));
        }))
        .toMaybe();
  }

  private MaybeTransformer<Cycle, Cycle> bootstrap() {
    return upstream -> upstream.switchIfEmpty(promptRestoreFromBackup()
        .flatMapMaybe(restoreFromBackup -> {
          if (!restoreFromBackup) {
            return Maybe.empty();
          }
          Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          intent.setType("application/pdf");


          startActivityForResult(intent, 1);

          UserInitActivity activity = (UserInitActivity) getActivity();
          if (activity == null) {
            return Maybe.empty();
          }
          return activity
              .fileLocation()
              .takeLast(1, 10, TimeUnit.SECONDS)
              .firstElement()
              .flatMap(uri -> {
                Cursor cursor = getActivity().getContentResolver().query(uri, null, null, null, null, null);
                try {
                  if (cursor != null && cursor.moveToFirst()) {
                    String displayName = cursor.getString(
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                    Timber.i("Display Name: %s", displayName);
                  }
                } finally {
                  cursor.close();
                }
                return Maybe.empty();
              });
        }));
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
  }

  @Override
  public void onUserInitialized(final FirebaseUser user) {
    updateStatus("Loading your data");
    if (DEBUG) Log.v(TAG, "Getting current cycleToShow");
    mDisposables.add(mCycleRepo.getCurrentCycle()
        .compose(tryUseLatestAsCurrent())
        .observeOn(AndroidSchedulers.mainThread())
        //.compose(bootstrap())
        .compose(initFirstCycle())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribeOn(Schedulers.computation())
        .subscribe(cycle -> {
          Intent intent = new Intent(getContext(), ChartEntryListActivity.class);
          startActivity(intent);
        }, throwable -> {
          Timber.e(throwable);
          showError("Could not find or create a cycle!");
        }));
  }

  private Single<Boolean> promptUseLatestAsCurrent() {
    return Single.create(e -> {
      new AlertDialog.Builder(getActivity())
          //set message, title, and icon
          .setTitle("Use latest cycle?")
          .setMessage("Would you like to use the latest cycle as the current? Probably recovering from an error...")
          .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
              e.onSuccess(true);
              dialog.dismiss();
            }
          })
          .setNegativeButton("No", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
              e.onSuccess(false);
              dialog.dismiss();
            }
          })
          .create().show();
    });
  }

  private Single<LocalDate> promptForStart() {
    return Single.create(e -> {
      updateStatus("Prompting for start of first cycleToShow.");
      updateStatus("Creating first cycleToShow");
      DatePickerDialog datePickerDialog = DatePickerDialog.newInstance(
          (view, year, monthOfYear, dayOfMonth) -> e.onSuccess(new LocalDate(year, monthOfYear + 1, dayOfMonth)));
      datePickerDialog.setTitle("First day of current cycleToShow");
      datePickerDialog.setMaxDate(Calendar.getInstance());
      datePickerDialog.show(getActivity().getFragmentManager(), "datepickerdialog");
    });
  }

  private Single<Boolean> promptRestoreFromBackup() {
    return Single.create(e -> {
      new AlertDialog.Builder(getActivity())
          //set message, title, and icon
          .setTitle("Restore from backup?")
          .setMessage("Would you like to restore from a backup (.chart file)?")
          .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
              e.onSuccess(true);
              dialog.dismiss();
            }
          })
          .setNegativeButton("No", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
              e.onSuccess(false);
              dialog.dismiss();
            }
          })
          .create().show();
    });
  }
}
