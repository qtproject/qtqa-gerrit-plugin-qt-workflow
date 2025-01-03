//
// Copyright (C) 2025 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.NotifyConfig.NotifyType;
import com.google.gerrit.exceptions.EmailException;
import com.google.gerrit.extensions.api.changes.RecipientType;
import com.google.gerrit.server.mail.send.ChangeEmail;
import com.google.gerrit.server.mail.send.ChangeEmail.ChangeEmailDecorator;
import com.google.gerrit.server.mail.send.OutgoingEmail;

public class QtBuildFailedEmailDecorator implements ChangeEmailDecorator {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected OutgoingEmail email;
  protected ChangeEmail changeEmail;

  public QtBuildFailedEmailDecorator() {}

  @Override
  public void init(OutgoingEmail email, ChangeEmail changeEmail) {
    this.email = email;
    this.changeEmail = changeEmail;
    changeEmail.markAsReply();

    // We want to send the email even if the "send only when in attention set" is enabled.
    changeEmail.setEmailOnlyAttentionSetIfEnabled(false);
  }

  @Override
  public void populateEmailContent() throws EmailException {

    changeEmail.addAuthors(RecipientType.TO);
    changeEmail.ccAllApprovals();
    changeEmail.bccStarredBy();
    changeEmail.includeWatchers(NotifyType.ALL_COMMENTS);
    changeEmail.includeWatchers(NotifyType.SUBMITTED_CHANGES);

    email.appendText(email.textTemplate("QtBuildFailed"));

    if (email.useHtml()) {
      email.appendHtml(email.soyHtmlTemplate("QtBuildFailedHtml"));
    }
  }
}
