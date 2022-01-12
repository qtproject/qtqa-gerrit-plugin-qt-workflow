//
// Copyright (C) 2022 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.server.mail.send.MailSoyTemplateProvider;
import com.google.inject.Singleton;
import java.util.HashSet;
import java.util.Set;

public class QtBuildFailedEmailTemplateRegister implements MailSoyTemplateProvider {

  public String getPath() {
    return "mail";
  }

  public Set<String> getFileNames() {
    Set<String> set = new HashSet<>();
    set.add("QtBuildFailed.soy");
    set.add("QtBuildFailedHtml.soy");
    return set;
  }
}
