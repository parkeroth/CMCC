package com.roamingroths.cmcc.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

/**
 * Created by parkeroth on 4/20/17.
 */

public class ChartEntryDataSource {
  private SQLiteDatabase db;
  private final DbHelper helper;

  public ChartEntryDataSource(Context context) {
    db = null;
    helper = new DbHelper(context);
  }

  public void open() {
    db = helper.getWritableDatabase();
  }

  public void close() {
    helper.close();
  }
}