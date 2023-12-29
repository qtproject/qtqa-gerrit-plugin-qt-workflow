//
// Copyright (C) 2023 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Singleton;

@Singleton
public class QtCiStatusStorage {

  public class CiResourceItem {
    String name;
    int running;
    int queue;
    int load;
  }

  private static CiResourceItem[] queues = null;

  public static CiResourceItem[] getData() {
    return queues;
  }

  public static String setData(String json) {
    Gson gson = new Gson();
    CiResourceItem[] q = null;
    try {
      q = gson.fromJson(json, CiResourceItem[].class);
    } catch (JsonSyntaxException e) {
      return "JsonSyntaxException: " + e.getMessage();
    }

    if (q == null) return "JSON string empty";

    queues = q;
    return null;
  }
}
