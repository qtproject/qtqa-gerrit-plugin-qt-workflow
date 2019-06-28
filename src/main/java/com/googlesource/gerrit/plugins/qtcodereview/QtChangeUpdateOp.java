//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.base.Strings;
import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.LabelId;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.server.ApprovalsUtil;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.change.LabelNormalizer;
import com.google.gerrit.server.notedb.ChangeUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.ChangeContext;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class QtChangeUpdateOp implements BatchUpdateOp {

    public interface Factory {
        QtChangeUpdateOp create(@Assisted("newStatus") Change.Status newStatus,
                                @Assisted("oldStatus") Change.Status oldStatus,
                                @Assisted("defaultMessage") String defaultMessage,
                                @Assisted("inputMessage") String inputMessage,
                                @Assisted("tag") String tag,
                                CodeReviewCommit copyApprovalsFrom);
    }

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private final Change.Status newStatus;
    private final Change.Status oldStatus;
    private final String defaultMessage;
    private final String inputMessage;
    private final String tag;
    private CodeReviewCommit copyApprovalsFrom;

    private Change change;
    private PatchSetApproval submitter;

    private final ChangeMessagesUtil cmUtil;
    private final ApprovalsUtil approvalsUtil;
    private final LabelNormalizer labelNormalizer;

    @Inject
    QtChangeUpdateOp(ChangeMessagesUtil cmUtil,
                     ApprovalsUtil approvalsUtil,
                     LabelNormalizer labelNormalizer,
                     @Nullable @Assisted("newStatus") Change.Status newStatus,
                     @Nullable @Assisted("oldStatus") Change.Status oldStatus,
                     @Nullable @Assisted("defaultMessage") String defaultMessage,
                     @Nullable @Assisted("inputMessage") String inputMessage,
                     @Nullable @Assisted("tag") String tag,
                     @Nullable @Assisted CodeReviewCommit copyApprovalsFrom) {
        this.cmUtil = cmUtil;
        this.approvalsUtil = approvalsUtil;
        this.labelNormalizer = labelNormalizer;
        this.newStatus = newStatus;
        this.oldStatus = oldStatus;
        this.defaultMessage = defaultMessage;
        this.inputMessage = inputMessage;
        this.tag = tag;
        this.copyApprovalsFrom = copyApprovalsFrom;
    }

    public Change getChange() {
        return change;
    }

    @Override
    public boolean updateChange(ChangeContext ctx) throws IOException, ResourceConflictException {
        boolean updated = false;
        change = ctx.getChange();
        PatchSet.Id psId = change.currentPatchSetId();
        ChangeUpdate update = ctx.getUpdate(psId);

        if (newStatus != null && (oldStatus == null || change.getStatus() == oldStatus)) {
            change.setStatus(newStatus);
            update.fixStatus(newStatus);
            updated = true;
        }

        if (copyApprovalsFrom != null) {
            Change.Id id = ctx.getChange().getId();
            PatchSet.Id oldPsId = copyApprovalsFrom.getPatchsetId();

            logger.atFine().log("Copy approval for %s oldps=%s newps=%s", id, oldPsId, psId);
            ChangeUpdate origPsUpdate = ctx.getUpdate(oldPsId);
            LabelNormalizer.Result normalized = approve(ctx, origPsUpdate);

            ChangeUpdate newPsUpdate = ctx.getUpdate(psId);

            saveApprovals(normalized, ctx, newPsUpdate, true);
            submitter = convertPatchSet(psId).apply(submitter);
            updated = true;
        }

        if (updated == true) {
            change.setLastUpdatedOn(ctx.getWhen());
        }

        if (defaultMessage != null) {
            cmUtil.addChangeMessage(update, newMessage(ctx));
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

    private LabelNormalizer.Result approve(ChangeContext ctx, ChangeUpdate update)
                                           throws IOException {
        PatchSet.Id psId = update.getPatchSetId();
        Map<PatchSetApproval.Key, PatchSetApproval> byKey = new HashMap<>();
        for (PatchSetApproval psa : approvalsUtil.byPatchSet(ctx.getNotes(),
                                                             psId,
                                                             ctx.getRevWalk(),
                                                             ctx.getRepoView().getConfig())) {
            byKey.put(psa.getKey(), psa);
        }

        submitter = ApprovalsUtil.newApproval(psId, ctx.getUser(), LabelId.legacySubmit(), 1, ctx.getWhen());
        byKey.put(submitter.getKey(), submitter);

        LabelNormalizer.Result normalized =  labelNormalizer.normalize(ctx.getNotes(), byKey.values());
        update.putApproval(submitter.getLabel(), submitter.getValue());
        saveApprovals(normalized, ctx, update, false);
        return normalized;
    }

    private void saveApprovals(LabelNormalizer.Result normalized,
                               ChangeContext ctx,
                               ChangeUpdate update,
                               boolean includeUnchanged) {
        PatchSet.Id psId = update.getPatchSetId();
        // FIXME, can this simply be removed? ctx.patchSetApprovals().upsert(convertPatchSet(normalized.getNormalized(), psId));
        // FIXME, can this simply be removed? ctx.patchSetApprovals().upsert(zero(convertPatchSet(normalized.deleted(), psId)));
        for (PatchSetApproval psa : normalized.updated()) {
            update.putApprovalFor(psa.getAccountId(), psa.getLabel(), psa.getValue());
        }
        for (PatchSetApproval psa : normalized.deleted()) {
            update.removeApprovalFor(psa.getAccountId(), psa.getLabel());
        }

        for (PatchSetApproval psa : normalized.unchanged()) {
            if (includeUnchanged || psa.isLegacySubmit()) {
                logger.atFine().log("Adding submit label %s", psa);
                update.putApprovalFor(psa.getAccountId(), psa.getLabel(), psa.getValue());
            }
        }
    }

    private Function<PatchSetApproval, PatchSetApproval> convertPatchSet(final PatchSet.Id psId) {
        return psa -> {
            if (psa.getPatchSetId().equals(psId)) {
                return psa;
            }
            return new PatchSetApproval(psId, psa);
        };
    }

    private Iterable<PatchSetApproval> convertPatchSet(Iterable<PatchSetApproval> approvals, PatchSet.Id psId) {
        return Iterables.transform(approvals, convertPatchSet(psId));
    }

    private Iterable<PatchSetApproval> zero(Iterable<PatchSetApproval> approvals) {
        return Iterables.transform(
            approvals,
            a -> {
                PatchSetApproval copy = new PatchSetApproval(a.getPatchSetId(), a);
                copy.setValue((short) 0);
                return copy;
            }
        );
    }

}
