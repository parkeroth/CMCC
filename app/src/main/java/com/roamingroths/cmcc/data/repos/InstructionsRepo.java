package com.roamingroths.cmcc.data.repos;

import com.google.android.material.appbar.AppBarLayout;
import com.roamingroths.cmcc.application.MyApplication;
import com.roamingroths.cmcc.data.db.InstructionDao;
import com.roamingroths.cmcc.data.entities.Instructions;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.subjects.BehaviorSubject;

public class InstructionsRepo {

  private final InstructionDao mInstructionDao;

  private final List<Instructions> mBogusInstructions = new LinkedList<>();
  private final BehaviorSubject<List<Instructions>> mInstructionsStream = BehaviorSubject.create();

  public InstructionsRepo(MyApplication myApp) {
    mInstructionDao = myApp.db().instructionDao();
    mInstructionsStream.onNext(mBogusInstructions);
  }

  public Flowable<List<Instructions>> getAll() {
    return mInstructionsStream.toFlowable(BackpressureStrategy.DROP);
  }

  public Completable addInstructions()
}
