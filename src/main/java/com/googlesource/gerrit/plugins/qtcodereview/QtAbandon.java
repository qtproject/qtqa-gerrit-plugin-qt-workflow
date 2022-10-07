//
// Copyright (C) 2019-22 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Change;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.PatchSetUtil;
import com.google.gerrit.server.change.ChangeJson;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;

// Standard Abandon is not allowed if already in a closed state
// This class handles transition from DEFERRED to ABANDONED

@Singleton
public class QtAbandon
    implements RestModifyView<ChangeResource, AbandonInput>, UiAction<ChangeResource> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final BatchUpdate.Factory updateFactory;
  private final ChangeJson.Factory json;
  private final PatchSetUtil psUtil;
  private final QtChangeUpdateOp.Factory qtUpdateFactory;

  @Inject
  QtAbandon(
      BatchUpdate.Factory updateFactory,
      ChangeJson.Factory json,
      PatchSetUtil psUtil,
      QtChangeUpdateOp.Factory qtUpdateFactory) {
    this.updateFactory = updateFactory;
    this.json = json;
    this.psUtil = psUtil;
    this.qtUpdateFactory = qtUpdateFactory;
  }

  @Override
  public Response<ChangeInfo> apply(ChangeResource rsrc, AbandonInput input)
      throws RestApiException, UpdateException, PermissionBackendException, IOException {
    Change change = rsrc.getChange();
    logger.atInfo().log("abandon %s", change);

    // Not allowed to abandon if the current patch set is locked.
    psUtil.checkPatchSetNotLocked(rsrc.getNotes());

    rsrc.permissions().check(ChangePermission.ABANDON);

    if (change.getStatus() != Change.Status.DEFERRED) {
      logger.atSevere().log("change %s status wrong %s", change.getId(), change.getStatus());
      throw new ResourceConflictException("change is " + ChangeUtil.status(change));
    }

    QtChangeUpdateOp op =
        qtUpdateFactory.create(
            Change.Status.ABANDONED,
            null,
            "Abandoned",
            input.message,
            ChangeMessagesUtil.TAG_ABANDON,
            null);
    try (BatchUpdate u =
        updateFactory.create(change.getProject(), rsrc.getUser(), TimeUtil.nowTs())) {
      u.addOp(rsrc.getId(), op).execute();
    }

    logger.atInfo().log("abandoned change %s,%s", change.getId(), change.getKey());

    change = op.getChange();
    return Response.ok(json.noOptions().format(change));
  }

  @Override
  public UiAction.Description getDescription(ChangeResource rsrc) {
    UiAction.Description description =
        new UiAction.Description()
            .setLabel("Abandon")
            .setTitle("Abandon the change")
            .setVisible(false);
    Change change = rsrc.getChange();
    if (change.getStatus() != Change.Status.DEFERRED) {
      return description;
    }

    try {
      if (psUtil.isPatchSetLocked(rsrc.getNotes())) {
        return description;
      }
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log(
          "Failed to check if the current patch set of change %s is locked", change.getId());
      return description;
    }

    return description.setVisible(rsrc.permissions().testOrFalse(ChangePermission.ABANDON));
  }
}
