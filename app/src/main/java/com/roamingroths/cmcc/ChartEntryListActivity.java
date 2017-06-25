package com.roamingroths.cmcc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.PreferenceManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.firebase.ui.auth.AuthUI;
import com.google.common.base.Preconditions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseError;
import com.roamingroths.cmcc.data.ChartEntry;
import com.roamingroths.cmcc.data.Cycle;
import com.roamingroths.cmcc.data.DataStore;
import com.roamingroths.cmcc.utils.Callbacks;
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog;

import org.joda.time.LocalDate;

import java.util.Calendar;

public class ChartEntryListActivity extends AppCompatActivity implements
    ChartEntryAdapter.OnClickHandler, ChartEntryAdapter.OnItemAddedHandler,
    SharedPreferences.OnSharedPreferenceChangeListener {

  public static final int RC_SIGN_IN = 1;

  private TextView mErrorView;
  private ProgressBar mProgressBar;
  private FloatingActionButton mFab;

  private RecyclerView mRecyclerView;
  private ChartEntryAdapter mChartEntryAdapter;
  private ChartEntryList mChartEntryList;

  private Cycle savedCycle;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    mErrorView = (TextView) findViewById(R.id.refresh_error);
    mProgressBar = (ProgressBar) findViewById(R.id.progress_bar);

    mChartEntryAdapter = new ChartEntryAdapter(this, this, this);

    mRecyclerView = (RecyclerView) findViewById(R.id.recyclerview_entry);
    boolean shouldReverseLayout = false;
    LinearLayoutManager layoutManager
        = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, shouldReverseLayout);
    mRecyclerView.setLayoutManager(layoutManager);
    mRecyclerView.setHasFixedSize(false);
    mRecyclerView.setAdapter(mChartEntryAdapter);

    // Init Firebase stuff
    final FirebaseUser user =
        Preconditions.checkNotNull(FirebaseAuth.getInstance().getCurrentUser());
    // Find the current cycle
    showProgress();

    Intent intentThatStartedThisActivity = Preconditions.checkNotNull(getIntent());
    Preconditions.checkState(intentThatStartedThisActivity.hasExtra(Cycle.class.getName()));
    Cycle cycle = intentThatStartedThisActivity.getParcelableExtra(Cycle.class.getName());
    attachAdapterToCycle(cycle);

    PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    if (key.equals("enable_pre_peak_yellow_stickers")
        || key.equals("enable_post_peak_yellow_stickers")) {
      runOnUiThread(new Runnable() {
        @Override
        public void run() {
          mChartEntryAdapter.notifyDataSetChanged();
        }
      });
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    switch (requestCode) {
      case RC_SIGN_IN:
        startActivity(new Intent(this, ChartEntryListActivity.class));
        break;
      case ChartEntryModifyActivity.MODIFY_REQUEST:
        switch (resultCode) {
          case ChartEntryModifyActivity.OK_RESPONSE:
            if (data.hasExtra(Cycle.class.getName())) {
              showProgress();
              log("Attaching to new cycle");
              attachAdapterToCycle((Cycle) data.getParcelableExtra(Cycle.class.getName()));
            }
            break;
        }
        break;
    }
  }

  private void attachAdapterToCycle(Cycle cycle) {
    savedCycle = cycle;
    getSupportActionBar().setTitle("Cycle starting " + cycle.startDateStr);
    log("Attaching to cycle starting " + cycle.startDateStr);
    mChartEntryAdapter.attachToCycle(cycle, Callbacks.singleUse(new Callbacks.HaltingCallback<Void>() {
      @Override
      public void acceptData(Void unused) {
        log("Hiding progress bar");
        showList();
      }
    }));
    log("Attached to cycle starting " + cycle.startDateStr);
  }

  @Nullable
  private Cycle detachAdapterFromCycle() {
    return mChartEntryAdapter.detachFromCycle();
  }

  @Override
  protected void onResume() {
    super.onResume();
    log("onResume");
    if (savedCycle != null) {
      log("Attaching to saved cycle: " + savedCycle.startDateStr);
      attachAdapterToCycle(savedCycle);
    }
    mRecyclerView.scrollToPosition(0);
  }

  @Override
  protected void onPause() {
    super.onPause();
    log("onPause");
    savedCycle = detachAdapterFromCycle();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.menu_main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    int id = item.getItemId();

    if (id == R.id.action_settings) {
      Intent startSettingsActivity = new Intent(this, SettingsActivity.class);
      startActivity(startSettingsActivity);
      return true;
    }

    if (id == R.id.action_list_cycles) {
      Intent startCycleList = new Intent(this, CycleListActivity.class);
      finish();
      startActivity(startCycleList);
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onClick(ChartEntry entry, int index) {
    Context context = this;
    Class destinationClass = ChartEntryModifyActivity.class;
    Intent intent = new Intent(context, destinationClass);
    intent.putExtra(Extras.ENTRY_DATE_STR, entry.getDateStr());
    intent.putExtra(Cycle.class.getName(), mChartEntryAdapter.getCycle());
    startActivityForResult(intent, ChartEntryModifyActivity.MODIFY_REQUEST);
  }

  private void showError() {
    mErrorView.setVisibility(View.VISIBLE);
    mRecyclerView.setVisibility(View.INVISIBLE);
    mProgressBar.setVisibility(View.INVISIBLE);
  }

  private void showList() {
    mRecyclerView.setVisibility(View.VISIBLE);
    mErrorView.setVisibility(View.INVISIBLE);
    mProgressBar.setVisibility(View.INVISIBLE);
  }

  private void showProgress() {
    mProgressBar.setVisibility(View.VISIBLE);
    mRecyclerView.setVisibility(View.INVISIBLE);
    mErrorView.setVisibility(View.INVISIBLE);
  }

  @Override
  public void onItemAdded(ChartEntry entry, int index) {
    mRecyclerView.scrollToPosition(index);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    log("onDestroy");
  }

  private void log(String message) {
    Log.v(ChartEntryListActivity.class.getName(), message);
  }
}