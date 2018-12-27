//
// Copyright (C) 2018 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.base.Strings;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.notedb.ChangeUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.ChangeContext;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;

public class QtChangeUpdateOp implements BatchUpdateOp {

    public interface Factory {
        QtChangeUpdateOp create(Change.Status newStatus,
                                @Assisted("defaultMessage") String defaultMessage,
                                @Assisted("inputMessage") String inputMessage,
                                @Assisted("tag") String tag);
    }

    private final Change.Status newStatus;
    private final String defaultMessage;
    private final String inputMessage;
    private final String tag;

    private Change change;

    private final ChangeMessagesUtil cmUtil;


    @Inject
    QtChangeUpdateOp(ChangeMessagesUtil cmUtil,
                     @Nullable @Assisted Change.Status newStatus,
                     @Nullable @Assisted("defaultMessage") String defaultMessage,
                     @Nullable @Assisted("inputMessage") String inputMessage,
                     @Nullable @Assisted("tag") String tag) {
        this.cmUtil = cmUtil;
        this.newStatus = newStatus;
        this.defaultMessage = defaultMessage;
        this.inputMessage = inputMessage;
        this.tag = tag;
    }

    public Change getChange() {
        return change;
    }

    @Override
    public boolean updateChange(ChangeContext ctx) throws OrmException, IOException, ResourceConflictException {
        boolean updated = false;
        change = ctx.getChange();
        PatchSet.Id psId = change.currentPatchSetId();
        ChangeUpdate update = ctx.getUpdate(psId);

        if (newStatus != null) {
            change.setStatus(newStatus);
            updated = true;
        }

        if (updated == true) {
            change.setLastUpdatedOn(ctx.getWhen());
        }

        if (defaultMessage != null) {
            cmUtil.addChangeMessage(ctx.getDb(), update, newMessage(ctx));
            updated = true;
        }

        return updated;
    }

    private ChangeMessage newMessage(ChangeContext ctx) {
        StringBuilder msg = new StringBuilder();
        msg.append(defaultMessage);
        if (!Strings.nullToEmpty(inputMessage).trim().isEmpty()) {
            msg.append("\n\n");
            msg.append(inputMessage.trim());
        }
        return ChangeMessagesUtil.newMessage(ctx, msg.toString(), tag);
    }

}
