//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.gerrit.exceptions.EmailException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.account.ProjectWatches.NotifyType;
import com.google.gerrit.server.mail.send.EmailArguments;
import com.google.gerrit.server.mail.send.ReplyToChangeSender;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.ArrayList;
import java.util.List;

// Send email when build has failed
public class QtBuildFailedSender extends ReplyToChangeSender {
    public interface Factory {
        QtBuildFailedSender create(Project.NameKey project, Change.Id id);
    }

    @Inject
    public QtBuildFailedSender(EmailArguments ea,
                               @Assisted Project.NameKey project,
                               @Assisted Change.Id id) {
        super(ea, "comment", newChangeData(ea, project, id));
    }

    @Override
    protected void init() throws EmailException {
        super.init();

        ccAllApprovals();
        bccStarredBy();
        includeWatchers(NotifyType.ALL_COMMENTS);
        removeUsersThatIgnoredTheChange();
    }

    @Override
    protected void formatChange() throws EmailException {
        appendText(textTemplate("Comment"));
    }

    @Override
    protected void setupSoyContext() {
        super.setupSoyContext();
        List<String> emptyList = new ArrayList<>();
        soyContext.put("commentFiles", emptyList);
    }

    @Override
    protected boolean supportsHtml() {
        return false;
    }
}
