//
// Copyright (C) 2021 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.mail.send.MergedSender;
import com.google.gerrit.server.mail.send.MessageIdGenerator;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Optional;

@Singleton
public class QtEmailSender {

  @Inject private MergedSender.Factory mergedSenderFactory;

  @Inject private QtBuildFailedSender.Factory qtBuildFailedSenderFactory;

  @Inject private MessageIdGenerator messageIdGenerator;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public void sendMergedEmail(Project.NameKey projectKey, Change change,
      Account.Id fromAccount) {
    try {
      MergedSender mcm = mergedSenderFactory.create(projectKey, change.getId(), Optional.empty());
      mcm.setFrom(fromAccount);
      mcm.setMessageId(
          messageIdGenerator.fromChangeUpdate(projectKey, change.currentPatchSetId()));
      mcm.send();
    } catch (Exception e) {
      logger.atWarning().log("Merged notification not sent for %s %s", change.getId(), e);
    }
  }

  public void sendBuildFailedEmail(Project.NameKey projectKey, Change change,
      Account.Id fromAccount, String message) {
    try {
      QtBuildFailedSender cm = qtBuildFailedSenderFactory.create(projectKey, change.getId());
      cm.setFrom(fromAccount);
      cm.setMessageId(
          messageIdGenerator.fromChangeUpdate(projectKey, change.currentPatchSetId()));
      cm.setChangeMessage(message, TimeUtil.nowTs());
      cm.send();
    } catch (Exception e) {
      logger.atWarning().log("Build Failed not sent notification for %s %s", change.getId(), e);
    }
  }
}
