//
// Copyright (C) 2021-25 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.mail.EmailFactories.CHANGE_MERGED;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.change.NotifyResolver;
import com.google.gerrit.server.mail.EmailFactories;
import com.google.gerrit.server.mail.send.ChangeEmail;
import com.google.gerrit.server.mail.send.MessageIdGenerator;
import com.google.gerrit.server.mail.send.OutgoingEmail;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.Instant;
import java.util.Optional;

@Singleton
public class QtEmailSender {

  @Inject private EmailFactories emailFactories;

  @Inject private MessageIdGenerator messageIdGenerator;

  private NotifyResolver.Result notify = NotifyResolver.Result.all();

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public void sendMergedEmail(Project.NameKey projectKey, Change change, Account.Id fromAccount) {
    try {
      ChangeEmail changeEmail =
          emailFactories.createChangeEmail(
              projectKey, change.getId(), emailFactories.createMergedChangeEmail(Optional.empty()));
      OutgoingEmail outgoingEmail = emailFactories.createOutgoingEmail(CHANGE_MERGED, changeEmail);
      if (fromAccount != null) {
        outgoingEmail.setFrom(fromAccount);
      }
      outgoingEmail.setNotify(notify);
      outgoingEmail.setMessageId(
          messageIdGenerator.fromChangeUpdate(projectKey, change.currentPatchSetId()));
      outgoingEmail.send();
    } catch (Exception e) {
      logger.atWarning().log("Merged notification not sent for %s %s", change.getId(), e);
    }
  }

  public void sendBuildFailedEmail(
      Project.NameKey projectKey, Change change, Account.Id fromAccount, String message) {
    try {
      ChangeEmail changeEmail =
          emailFactories.createChangeEmail(
              projectKey, change.getId(), new QtBuildFailedEmailDecorator());
      changeEmail.setChangeMessage(message, Instant.now());
      OutgoingEmail outgoingEmail =
          emailFactories.createOutgoingEmail("qtbuildfailed", changeEmail);
      if (fromAccount != null) {
        outgoingEmail.setFrom(fromAccount);
      }
      outgoingEmail.setNotify(notify);
      outgoingEmail.setMessageId(
          messageIdGenerator.fromChangeUpdate(projectKey, change.currentPatchSetId()));
      outgoingEmail.send();
    } catch (Exception e) {
      logger.atWarning().log("Build Failed not sent notification for %s %s", change.getId(), e);
    }
  }
}
