package com.roamingroths.cmcc.logic.profile;

import com.roamingroths.cmcc.crypto.Cipherable;

import javax.crypto.SecretKey;

/**
 * Created by parkeroth on 1/13/18.
 */

public class Profile implements Cipherable {

  private transient volatile SecretKey mKey;

  public String mPreferredName;
  public SystemGoal mGoal;
  public int heightCm;
  public int weightKg;

  public Profile(SecretKey key) {
    mKey = key;
  }

  @Override
  public SecretKey getKey() {
    return mKey;
  }

  @Override
  public void swapKey(SecretKey key) {
    mKey = key;
  }

  public enum SystemGoal {
    ACHIEVE, AVOID;
  }
}