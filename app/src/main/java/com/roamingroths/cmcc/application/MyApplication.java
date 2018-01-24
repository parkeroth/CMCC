package com.roamingroths.cmcc.application;

import android.app.Application;
import android.content.Context;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import com.roamingroths.cmcc.R;
import com.roamingroths.cmcc.crypto.CryptoUtil;
import com.roamingroths.cmcc.logic.profile.Profile;
import com.roamingroths.cmcc.providers.AppStateProvider;
import com.roamingroths.cmcc.providers.ChartEntryProvider;
import com.roamingroths.cmcc.providers.CryptoProvider;
import com.roamingroths.cmcc.providers.CycleEntryProvider;
import com.roamingroths.cmcc.providers.CycleProvider;
import com.roamingroths.cmcc.providers.KeyProvider;
import com.roamingroths.cmcc.providers.ProfileProvider;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.reactivex.functions.Action;
import io.reactivex.functions.Function;

/**
 * Created by parkeroth on 5/15/17.
 */

public class MyApplication extends Application {

  private static final boolean DEBUG = true;
  private static final String TAG = MyApplication.class.getSimpleName();

  private static CryptoUtil mCryptoUtil;
  private static Providers mProviders;

  @Override
  public void onCreate() {
    super.onCreate();
    if (DEBUG) Log.v(TAG, "onCreate()");
    FirebaseApp.initializeApp(this);
    //FirebaseDatabase.getInstance().setPersistenceEnabled(true);
    Security.addProvider(new BouncyCastleProvider());
    PreferenceManager.setDefaultValues(this, R.xml.pref_settings, false);
  }

  public static Completable initProviders(final FirebaseUser user, Maybe<String> phoneNumber, final Context context) {
    Log.i(TAG, "Creating crypto util for user: " + user.getUid());
    Single<CryptoUtil> cryptoUtil =
        CryptoProvider.forDb(FirebaseDatabase.getInstance()).createCryptoUtil(user, phoneNumber);
    return cryptoUtil.flatMapCompletable(new Function<CryptoUtil, CompletableSource>() {
      @Override
      public CompletableSource apply(CryptoUtil cryptoUtil) throws Exception {
        Log.i(TAG, "Crypto initialization complete");
        mCryptoUtil = cryptoUtil;
        mProviders = new Providers(FirebaseDatabase.getInstance(), mCryptoUtil, user);
        return mProviders.initialize(user, context).doOnComplete(new Action() {
          @Override
          public void run() throws Exception {
            Log.i(TAG, "Provider initialization complete");
          }
        });
      }
    });
  }

  public static Providers getProviders() {
    return mProviders;
  }

  public static class Providers {
    private final CycleProvider mCycleProvider;
    private final KeyProvider mKeyProvider;
    private final CycleEntryProvider mCycleEntryProvider;
    private final ChartEntryProvider mChartEntryProvider;
    private final ProfileProvider mProfileProvider;
    private final AppStateProvider mAppStateProvider;

    Providers(FirebaseDatabase db, CryptoUtil cryptoUtil, FirebaseUser currentUser) {
      mKeyProvider = new KeyProvider(cryptoUtil, db, currentUser);
      mProfileProvider = new ProfileProvider(db, currentUser, cryptoUtil, mKeyProvider);
      mChartEntryProvider = new ChartEntryProvider(db, cryptoUtil);
      mCycleProvider = new CycleProvider(db, mKeyProvider, mChartEntryProvider);
      mCycleEntryProvider = new CycleEntryProvider(mChartEntryProvider);
      mAppStateProvider = new AppStateProvider(mProfileProvider, mCycleProvider, mChartEntryProvider, currentUser);
    }

    public Completable initialize(FirebaseUser user, Context context) {
      if (DEBUG) Log.v(TAG, "Initializing providers");
      return Completable
          .mergeArray(
              mCycleProvider.initCache(user),
              mProfileProvider.init(context, Profile.SystemGoal.AVOID))
          .andThen(mChartEntryProvider.initCache(mCycleProvider.getAllCycles(user), 2));
    }

    public ProfileProvider forProfile() {
      return mProfileProvider;
    }

    public CycleEntryProvider forCycleEntry() {
      return mCycleEntryProvider;
    }

    public CycleProvider forCycle() {
      return mCycleProvider;
    }

    public ChartEntryProvider forChartEntry() {
      return mChartEntryProvider;
    }

    public AppStateProvider forAppState() {
      return mAppStateProvider;
    }
  }
}
