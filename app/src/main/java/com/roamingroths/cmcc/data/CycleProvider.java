package com.roamingroths.cmcc.data;

import android.support.annotation.Nullable;
import android.util.Log;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.roamingroths.cmcc.crypto.CryptoUtil;
import com.roamingroths.cmcc.crypto.RxCryptoUtil;
import com.roamingroths.cmcc.logic.ChartEntry;
import com.roamingroths.cmcc.logic.Cycle;
import com.roamingroths.cmcc.logic.Entry;
import com.roamingroths.cmcc.utils.Callbacks;
import com.roamingroths.cmcc.utils.Callbacks.Callback;
import com.roamingroths.cmcc.utils.DateUtil;
import com.roamingroths.cmcc.utils.FirebaseUtil;
import com.roamingroths.cmcc.utils.Listeners;

import org.joda.time.LocalDate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import durdinapps.rxfirebase2.RxFirebaseDatabase;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Maybe;
import io.reactivex.MaybeSource;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.internal.functions.Functions;

/**
 * Created by parkeroth on 9/2/17.
 */
public class CycleProvider {

  // TODO: Remove FirebaseAuth stuff

  private static final boolean DEBUG = true;
  private static final String TAG = CycleProvider.class.getSimpleName();

  private final FirebaseDatabase db;
  private final RxCryptoUtil cryptoUtil;
  private final CycleKeyProvider cycleKeyProvider;
  private final ImmutableMap<Class<? extends Entry>, EntryProvider> entryProviders;

  public static CycleProvider forDb(FirebaseDatabase db) {
    RxCryptoUtil cryptoUtil = CryptoProvider.forDb(db).createCryptoUtil().blockingGet();
    return forDb(db, cryptoUtil);
  }

  public static CycleProvider forDb(FirebaseDatabase db, RxCryptoUtil cryptoUtil) {
    return new CycleProvider(
        db,
        cryptoUtil,
        CycleKeyProvider.forDb(db, cryptoUtil),
        ChartEntryProvider.forDb(db, cryptoUtil),
        WellnessEntryProvider.forDb(db, cryptoUtil),
        SymptomEntryProvider.forDb(db, cryptoUtil));
  }

  private CycleProvider(
      FirebaseDatabase db, RxCryptoUtil cryptoUtil, CycleKeyProvider cycleKeyProvider, ChartEntryProvider chartEntryProvider, WellnessEntryProvider wellnessEntryProvider, SymptomEntryProvider symptomEntryProvider) {
    this.db = db;
    this.cryptoUtil = cryptoUtil;
    this.cycleKeyProvider = cycleKeyProvider;
    entryProviders = ImmutableMap.<Class<? extends Entry>, EntryProvider>builder()
        .put(chartEntryProvider.getEntryClazz(), chartEntryProvider)
        .put(wellnessEntryProvider.getEntryClazz(), wellnessEntryProvider)
        .put(symptomEntryProvider.getEntryClazz(), symptomEntryProvider).build();
  }

  public <E extends Entry> EntryProvider<E> getProviderForEntry(E entry) {
    return getProviderForClazz((Class<E>) entry.getClass());
  }

  public <E extends Entry> EntryProvider<E> getProviderForClazz(Class<E> clazz) {
    return entryProviders.get(clazz);
  }

  public Collection<EntryProvider> getEntryProviders() {
    return entryProviders.values();
  }

  public CycleKeyProvider getCycleKeyProvider() {
    return cycleKeyProvider;
  }

  public void attachListener(ChildEventListener listener, String userId) {
    DatabaseReference ref = reference(userId);
    ref.addChildEventListener(listener);
    ref.keepSynced(true);
  }

  @Deprecated
  public void maybeCreateNewEntries(Cycle cycle, final Callback<Void> doneCallback) {
    if (cycle.endDate != null) {
      logV("No entries to add, end date set");
      doneCallback.acceptData(null);
      return;
    }
    final AtomicInteger providersToCheck = new AtomicInteger(entryProviders.size());
    for (EntryProvider provider : entryProviders.values()) {
      provider.maybeAddNewEntries(cycle, new Callbacks.ErrorForwardingCallback<Void>(doneCallback) {
        @Override
        public void acceptData(Void data) {
          if (providersToCheck.decrementAndGet() == 0) {
            doneCallback.acceptData(null);
          }
        }
      });
    }
  }

  public void detachListener(ChildEventListener listener, String userId) {
    reference(userId).removeEventListener(listener);
  }

  @Deprecated
  public void putCycle(final String userId, final Cycle cycle, final Callback<Cycle> callback) {
    // Store key
    Map<String, Object> updates = new HashMap<>();
    updates.put("previous-cycle-id", cycle.previousCycleId);
    updates.put("next-cycle-id", cycle.nextCycleId);
    updates.put("start-date", cycle.startDateStr);
    updates.put("end-date", DateUtil.toWireStr(cycle.endDate));
    reference(userId, cycle.id).updateChildren(updates, Listeners.completionListener(callback, new Runnable() {
      @Override
      public void run() {
        cycleKeyProvider.forCycle(cycle.id).putChartKeys(cycle.keys, userId, new Callbacks.ErrorForwardingCallback<Void>(callback) {
          @Override
          public void acceptData(Void data) {
            logV("Storing keys for cycle: " + cycle.id);
            callback.acceptData(cycle);
          }
        });
      }
    }));
  }

  @Deprecated
  public void createCycle(
      final String userId,
      @Nullable Cycle previousCycle,
      @Nullable Cycle nextCycle,
      final LocalDate startDate,
      final @Nullable LocalDate endDate,
      final Callback<Cycle> callback) {
    DatabaseReference cycleRef = reference(userId).push();
    logV("Creating new cycle: " + cycleRef.getKey());
    final String cycleId = cycleRef.getKey();
    Cycle.Keys keys = new Cycle.Keys(
        CryptoUtil.createSecretKey(), CryptoUtil.createSecretKey(), CryptoUtil.createSecretKey());
    final Cycle cycle = new Cycle(
        cycleId,
        (previousCycle == null) ? null : previousCycle.id,
        (nextCycle == null) ? null : nextCycle.id,
        startDate,
        endDate,
        keys);
    putCycle(userId, cycle, new Callbacks.ErrorForwardingCallback<Cycle>(callback) {
      @Override
      public void acceptData(Cycle data) {
        maybeCreateNewEntries(cycle, new Callbacks.ErrorForwardingCallback<Void>(callback) {
          @Override
          public void acceptData(Void data) {
            callback.acceptData(cycle);
          }
        });
      }
    });
  }

  public Completable putCycleRx(final String userId, final Cycle cycle) {
    Map<String, Object> updates = new HashMap<>();
    updates.put("previous-cycle-id", cycle.previousCycleId);
    updates.put("next-cycle-id", cycle.nextCycleId);
    updates.put("start-date", cycle.startDateStr);
    updates.put("end-date", DateUtil.toWireStr(cycle.endDate));
    return RxFirebaseDatabase.updateChildren(reference(userId, cycle.id), updates)
        .andThen(cycleKeyProvider.putChartKeysRx(cycle.keys, cycle.id, userId));
  }

  public Single<Cycle> createCycleRx(
      final String userId,
      @Nullable Cycle previousCycle,
      @Nullable Cycle nextCycle,
      final LocalDate startDate,
      final @Nullable LocalDate endDate) {
    DatabaseReference cycleRef = reference(userId).push();
    logV("Creating new cycle: " + cycleRef.getKey());
    final String cycleId = cycleRef.getKey();
    Cycle.Keys keys = new Cycle.Keys(
        CryptoUtil.createSecretKey(), CryptoUtil.createSecretKey(), CryptoUtil.createSecretKey());
    final Cycle cycle = new Cycle(
        cycleId,
        (previousCycle == null) ? null : previousCycle.id,
        (nextCycle == null) ? null : nextCycle.id,
        startDate,
        endDate,
        keys);
    return putCycleRx(userId, cycle).andThen(Single.just(cycle));
  }

  public Observable<Cycle> getCyclesRx(final String userId) {
    return RxFirebaseDatabase.observeSingleValueEvent(reference(userId), Functions.<DataSnapshot>identity())
        .flatMapObservable(new Function<DataSnapshot, ObservableSource<Cycle>>() {
          @Override
          public ObservableSource<Cycle> apply(@NonNull DataSnapshot dataSnapshot) throws Exception {
            Set<Maybe<Cycle>> cycles = new HashSet<>();
            for (DataSnapshot child : dataSnapshot.getChildren()) {
              cycles.add(cycleKeyProvider.getChartKeys(child.getKey(), userId).map(Cycle.fromSnapshot(child)));
            }
            return Maybe.merge(cycles).toObservable();
          }
        });
  }

  public Maybe<Cycle> getCurrentCycle(final String userId) {
    return getCyclesRx(userId)
        .filter(new io.reactivex.functions.Predicate<Cycle>() {
          @Override
          public boolean test(@NonNull Cycle cycle) throws Exception {
            return cycle.endDate == null;
          }
        })
        .firstElement();
  }

  public Single<Cycle> getOrCreateCurrentCycle(final String userId, Single<LocalDate> startOfFirstCycle) {
    return getCurrentCycle(userId)
        .switchIfEmpty(startOfFirstCycle.flatMapMaybe(new Function<LocalDate, MaybeSource<? extends Cycle>>() {
          @Override
          public MaybeSource<? extends Cycle> apply(@NonNull LocalDate startDate) throws Exception {
            return createCycleRx(userId, null, null, startDate, null).toMaybe();
          }
        }))
        .toSingle();
  }

  @Deprecated
  private void getCycles(
      final String userId, final Predicate<DataSnapshot> fetchPredicate, boolean criticalRead, final Callback<Collection<Cycle>> callback) {
    logV("Fetching cycles");
    ValueEventListener listener = new Listeners.SimpleValueEventListener(callback) {
      @Override
      public void onDataChange(DataSnapshot dataSnapshot) {
        logV("Received " + dataSnapshot.getChildrenCount() + " cycles");
        if (dataSnapshot.getChildrenCount() == 0) {
          callback.acceptData(ImmutableSet.<Cycle>of());
        }
        final Set<DataSnapshot> snapshots = new HashSet<>();
        for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
          if (fetchPredicate.apply(snapshot)) {
            snapshots.add(snapshot);
          }
        }
        final Map<String, Cycle> cycles = Maps.newConcurrentMap();
        for (final DataSnapshot snapshot : snapshots) {
          final String cycleId = snapshot.getKey();
          cycleKeyProvider.forCycle(cycleId).getChartKeys(userId, new Callbacks.ErrorForwardingCallback<Cycle.Keys>(callback) {
            @Override
            public void acceptData(Cycle.Keys keys) {
              Cycle cycle = Cycle.fromSnapshot(snapshot, keys);
              cycles.put(cycle.id, cycle);
              if (cycles.size() == snapshots.size()) {
                callback.acceptData(cycles.values());
              }
            }

            @Override
            public void handleNotFound() {
              Log.w("CycleProvider", "Could not find key for user " + userId + " cycle " + cycleId);
              callback.handleNotFound();
            }
          });
        }
      }
    };
    DatabaseReference ref = reference(userId);
    if (criticalRead) {
      FirebaseUtil.criticalRead(ref, callback, listener);
    } else {
      ref.addListenerForSingleValueEvent(listener);
    }
  }

  public void getAllCycles(String userId, Callback<Collection<Cycle>> callback) {
    getCycles(userId, Predicates.<DataSnapshot>alwaysTrue(), false, callback);
  }

  @Deprecated
  private void dropCycle(final String cycleId, final String userId, final Runnable onFinish) {
    logV("Dropping entries for cycle: " + cycleId);
    db.getReference("entries").child(cycleId).removeValue(new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
        logV("Dropping keys for cycle: " + cycleId);
        db.getReference("keys").child(cycleId).removeValue(new DatabaseReference.CompletionListener() {
          @Override
          public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
            logV("Dropping cycle: " + cycleId);
            reference(userId, cycleId).removeValue();
            onFinish.run();
          }
        });
      }
    });
  }

  private Completable dropCycle(String cycleId, String userId) {
    Completable dropEntries = RxFirebaseDatabase.removeValue(db.getReference("entries").child(cycleId));
    Completable dropKeys = cycleKeyProvider.dropKeys(cycleId);
    Completable dropCycle = RxFirebaseDatabase.removeValue(reference(userId, cycleId));
    return Completable.mergeArray(dropEntries, dropKeys, dropCycle);
  }

  public Completable dropCycles() {
    final FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
    DatabaseReference referenceToCycles = reference(user.getUid());
    return RxFirebaseDatabase.observeSingleValueEvent(referenceToCycles)
        .flatMapCompletable(new Function<DataSnapshot, CompletableSource>() {
          @Override
          public CompletableSource apply(@NonNull DataSnapshot snapshot) throws Exception {
            List<Completable> completables = new ArrayList<>();
            for (DataSnapshot cycleSnapshot : snapshot.getChildren()) {
              completables.add(dropCycle(cycleSnapshot.getKey(), user.getUid()));
            }
            return Completable.merge(completables);
          }
        });
  }

  public Maybe<Cycle> getCycle(final String userId, final @Nullable String cycleId) {
    if (Strings.isNullOrEmpty(cycleId)) {
      return Maybe.empty();
    }
    return cycleKeyProvider.getChartKeys(cycleId, userId)
        .flatMap(new Function<Cycle.Keys, MaybeSource<Cycle>>() {
          @Override
          public MaybeSource<Cycle> apply(final Cycle.Keys keys) throws Exception {
            DatabaseReference referenceToCycle = reference(userId, cycleId);
            return RxFirebaseDatabase.observeSingleValueEvent(referenceToCycle)
                .map(new Function<DataSnapshot, Cycle>() {
                  @Override
                  public Cycle apply(DataSnapshot dataSnapshot) throws Exception {
                    return Cycle.fromSnapshot(dataSnapshot, keys);
                  }
                });
          }
        });
  }

  @Deprecated
  public void getCycle(
      final String userId, final @Nullable String cycleId, final Callback<Cycle> callback) {
    if (Strings.isNullOrEmpty(cycleId)) {
      callback.acceptData(null);
      return;
    }
    cycleKeyProvider.forCycle(cycleId).getChartKeys(userId, new Callbacks.ErrorForwardingCallback<Cycle.Keys>(callback) {
      @Override
      public void acceptData(final Cycle.Keys keys) {
        reference(userId, cycleId).addListenerForSingleValueEvent(new Listeners.SimpleValueEventListener(callback) {
          @Override
          public void onDataChange(DataSnapshot dataSnapshot) {
            callback.acceptData(Cycle.fromSnapshot(dataSnapshot, keys));
          }
        });
      }
    });
  }

  public Single<Cycle> combineCycleRx(final String userId, final Cycle currentCycle) {
    Single<Cycle> previousCycle = getCycle(userId, currentCycle.id).toSingle().cache();

    Single<Cycle> updateNextAndReturnPrevious = previousCycle
        .flatMap(new Function<Cycle, Single<Cycle>>() {
          @Override
          public Single<Cycle> apply(Cycle previousCycle) throws Exception {
            if (previousCycle.nextCycleId == null) {
              if (DEBUG) Log.v(TAG, "No next cycle, skipping update");
              return Single.just(previousCycle);
            }
            if (DEBUG) Log.v(TAG, currentCycle.nextCycleId + " previous -> " + previousCycle.id);
            previousCycle.nextCycleId = currentCycle.nextCycleId;
            return RxFirebaseDatabase.setValue(reference(userId, currentCycle.nextCycleId).child("previous_cycle_id"), previousCycle.id)
                .andThen(Single.just(previousCycle));
          }
        });

    Completable updatePrevious = previousCycle
        .flatMapCompletable(new Function<Cycle, CompletableSource>() {
          @Override
          public CompletableSource apply(Cycle previousCycle) throws Exception {
            if (DEBUG) Log.v(TAG, "Updating previous cycle fields");
            Map<String, Object> updates = new HashMap<>();
            updates.put("next-cycle-id", currentCycle.nextCycleId);
            previousCycle.nextCycleId = currentCycle.nextCycleId;
            updates.put("end-date", DateUtil.toWireStr(currentCycle.endDate));
            previousCycle.endDate = currentCycle.endDate;
            return RxFirebaseDatabase.updateChildren(reference(userId, previousCycle.id), updates);
          }
        });

    Completable moveEntries = previousCycle
        .flatMapCompletable(new Function<Cycle, CompletableSource>() {
          @Override
          public CompletableSource apply(Cycle previousCycle) throws Exception {
            if (DEBUG) Log.v(TAG, "Moving entries");
            Set<Completable> entryMoves = new HashSet<>();
            for (final EntryProvider provider : entryProviders.values()) {
              entryMoves.add(provider.moveEntries(currentCycle, previousCycle, Predicates.alwaysTrue()));
            }
            return Completable.merge(entryMoves);
          }
        }).andThen(cycleKeyProvider.dropKeys(currentCycle.id));

    Completable dropCycle = dropCycle(currentCycle.id, userId);

    return Completable.mergeArray(updatePrevious, moveEntries).andThen(dropCycle).andThen(updateNextAndReturnPrevious);
  }

  public Single<Cycle> splitCycleRx(final String userId, final Cycle currentCycle, Single<ChartEntry> firstEntry) {
    return firstEntry.flatMap(new Function<ChartEntry, SingleSource<Cycle>>() {
      @Override
      public SingleSource<Cycle> apply(final ChartEntry firstEntry) throws Exception {
        if (DEBUG) Log.v(TAG, "First entry: " + firstEntry.getDateStr());

        Single<Cycle> newCycle = getCycle(userId, currentCycle.nextCycleId)
            .flatMap(new Function<Cycle, MaybeSource<Cycle>>() {
              @Override
              public MaybeSource<Cycle> apply(Cycle nextCycle) throws Exception {
                if (DEBUG) Log.v(TAG, "Create new cycle with next");
                return createCycleRx(
                    userId, currentCycle, nextCycle, firstEntry.getDate(), currentCycle.endDate)
                    .toMaybe();
              }
            })
            .switchIfEmpty(
                createCycleRx(userId, currentCycle, null, firstEntry.getDate(), currentCycle.endDate).toMaybe())
            .toSingle().cache();

        Completable updateNextPrevious = newCycle.flatMapCompletable(new Function<Cycle, Completable>() {
          @Override
          public Completable apply(Cycle newCycle) throws Exception {
            if (DEBUG) Log.v(TAG, "Update next's previous.");
            if (Strings.isNullOrEmpty(newCycle.nextCycleId)) {
              return Completable.complete().doOnComplete(new Action() {
                @Override
                public void run() throws Exception {
                  if (DEBUG) Log.v(TAG, "Done updating next's previous.");
                }
              });
            }
            return RxFirebaseDatabase.setValue(reference(userId, newCycle.nextCycleId).child("previous-cycle-id"), newCycle.id).doOnComplete(new Action() {
              @Override
              public void run() throws Exception {
                if (DEBUG) Log.v(TAG, "Done updating next's previous.");
              }
            });
          }
        });

        Completable updateCurrentNext = newCycle.flatMapCompletable(new Function<Cycle, Completable>() {
          @Override
          public Completable apply(Cycle newCycle) throws Exception {
            if (DEBUG) Log.v(TAG, "Update current's fields.");
            Map<String, Object> updates = new HashMap<>();
            updates.put("next-cycle-id", newCycle.id);
            updates.put("end-date", DateUtil.toWireStr(firstEntry.getDate().minusDays(1)));
            return RxFirebaseDatabase.updateChildren(reference(userId, currentCycle.id), updates).doOnComplete(new Action() {
              @Override
              public void run() throws Exception {
                if (DEBUG) Log.v(TAG, "Done updating current's fields.");
              }
            });
          }
        });

        Completable moveEntries = newCycle.flatMapCompletable(new Function<Cycle, CompletableSource>() {
          @Override
          public CompletableSource apply(Cycle newCycle) throws Exception {
            if (DEBUG) Log.v(TAG, "Moving entries.");
            final Predicate<LocalDate> ifEqualOrAfter = new Predicate<LocalDate>() {
              @Override
              public boolean apply(LocalDate entryDate) {
                return entryDate.equals(firstEntry.getDate()) || entryDate.isAfter(firstEntry.getDate());
              }
            };
            Set<Completable> entryMoves = new HashSet<>();
            for (final EntryProvider provider : entryProviders.values()) {
              entryMoves.add(provider.moveEntries(currentCycle, newCycle, ifEqualOrAfter));
            }
            return Completable.merge(entryMoves).doOnComplete(new Action() {
              @Override
              public void run() throws Exception {
                if (DEBUG) Log.v(TAG, "Done moving entries.");
              }
            });
          }
        });

        return Completable.mergeArray(updateNextPrevious, updateCurrentNext, moveEntries)
            .andThen(newCycle)
            .doOnSuccess(new Consumer<Cycle>() {
              @Override
              public void accept(Cycle cycle) throws Exception {
                if (DEBUG) Log.v(TAG, "Returning cycle: " + cycle.id);
              }
            });
      }
    });
  }

  private DatabaseReference reference(String userId, String cycleId) {
    return reference(userId).child(cycleId);
  }

  private DatabaseReference reference(String userId) {
    return db.getReference("cycles").child(userId);
  }

  private void logV(String message) {
    Log.v("CycleProvider", message);
  }
}
