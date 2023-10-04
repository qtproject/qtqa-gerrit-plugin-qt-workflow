//
// Copyright (C) 2019-23 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.project.ProjectCache.illegalState;
import static com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType.CHANGE_MODIFICATION;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Change;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.RestoreInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.PatchSetUtil;
import com.google.gerrit.server.change.ChangeJson;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;

@Singleton
class QtReOpen implements RestModifyView<ChangeResource, RestoreInput>, UiAction<ChangeResource> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final BatchUpdate.Factory updateFactory;
  private final ChangeJson.Factory json;
  private final PatchSetUtil psUtil;
  private final ProjectCache projectCache;
  private final QtChangeUpdateOp.Factory qtUpdateFactory;

  @Inject
  QtReOpen(
      BatchUpdate.Factory updateFactory,
      ChangeJson.Factory json,
      PatchSetUtil psUtil,
      ProjectCache projectCache,
      QtChangeUpdateOp.Factory qtUpdateFactory) {
    this.updateFactory = updateFactory;
    this.json = json;
    this.psUtil = psUtil;
    this.projectCache = projectCache;
    this.qtUpdateFactory = qtUpdateFactory;
  }

  @Override
  public Response<ChangeInfo> apply(ChangeResource rsrc, RestoreInput input)
      throws RestApiException, UpdateException, PermissionBackendException, IOException {
    Change change = rsrc.getChange();
    logger.atInfo().log("reopen %s", change);

    // Not allowed to restore if the current patch set is locked.
    psUtil.checkPatchSetNotLocked(rsrc.getNotes());

    // Use same permission as Restore. Note that Abandon permission grants the
    // Restore if the user also has push permission on the change’s destination ref.
    rsrc.permissions().check(ChangePermission.RESTORE);
    projectCache
        .get(rsrc.getProject())
        .orElseThrow(illegalState(rsrc.getProject()))
        .checkStatePermitsWrite();

    if (change.getStatus() != Change.Status.DEFERRED) {
      logger.atSevere().log("reopen %s status wrong %s", change.getId(), change.getStatus());
      throw new ResourceConflictException("change is " + ChangeUtil.status(change));
    }

    QtChangeUpdateOp op =
        qtUpdateFactory.create(
            Change.Status.NEW,
            Change.Status.DEFERRED,
            "Reopened",
            input.message,
            QtUtil.TAG_REOPENED,
            null);
    try (RefUpdateContext ctx = RefUpdateContext.open(CHANGE_MODIFICATION)) {
      try (BatchUpdate u =
            updateFactory.create(change.getProject(), rsrc.getUser(), TimeUtil.now())) {
        u.addOp(rsrc.getId(), op).execute();
      }
      change = op.getChange();
      logger.atInfo().log("reopened  %s,%s", change.getId(), change.getKey());
      return Response.ok(json.noOptions().format(change));
    }
  }
  @Override
  public UiAction.Description getDescription(ChangeResource rsrc) {
    UiAction.Description description =
        new UiAction.Description()
            .setLabel("Reopen")
            .setTitle("Reopen the change")
            .setVisible(false);

    Change change = rsrc.getChange();
    if (change.getStatus() != Change.Status.DEFERRED) {
      return description;
    }

    try {
      if (!projectCache.get(rsrc.getProject()).map(ProjectState::statePermitsRead).orElse(false)) {
        return description;
      }
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log(
          "Failed to check if project state permits write: %s", rsrc.getProject());
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

    boolean visible = rsrc.permissions().testOrFalse(ChangePermission.RESTORE);
    return description.setVisible(visible);
  }
}
