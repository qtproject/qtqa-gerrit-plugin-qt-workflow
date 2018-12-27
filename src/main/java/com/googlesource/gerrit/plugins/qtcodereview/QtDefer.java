//
// Copyright (C) 2019 The Qt Company
// Modified from https://gerrit.googlesource.com/gerrit/+/refs/heads/stable-2.16/java/com/google/gerrit/server/restapi/change/Abandon.java
//
// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.PatchSetUtil;
import com.google.gerrit.server.change.ChangeJson;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.RetryHelper;
import com.google.gerrit.server.update.RetryingRestModifyView;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;


@Singleton
class QtDefer extends RetryingRestModifyView<ChangeResource, AbandonInput, ChangeInfo>
              implements UiAction<ChangeResource> {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private final Provider<ReviewDb> dbProvider;
    private final ChangeJson.Factory json;
    private final PatchSetUtil psUtil;
    private final QtChangeUpdateOp.Factory qtUpdateFactory;

    @Inject
    QtDefer(Provider<ReviewDb> dbProvider,
            ChangeJson.Factory json,
            RetryHelper retryHelper,
            PatchSetUtil psUtil,
            QtChangeUpdateOp.Factory qtUpdateFactory) {
        super(retryHelper);
        this.dbProvider = dbProvider;
        this.json = json;
        this.psUtil = psUtil;
        this.qtUpdateFactory = qtUpdateFactory;
    }

    @Override
    protected ChangeInfo applyImpl(BatchUpdate.Factory updateFactory,
                                   ChangeResource rsrc,
                                   AbandonInput input)
                                   throws RestApiException, UpdateException,
                                          OrmException, PermissionBackendException,
                                          IOException {
        Change change = rsrc.getChange();
        logger.atInfo().log("qtcodereview: defer %s", rsrc.getChange().toString());

        // Not allowed to defer if the current patch set is locked.
        psUtil.checkPatchSetNotLocked(rsrc.getNotes());

        // Defer uses same permission as abandon
        rsrc.permissions().database(dbProvider).check(ChangePermission.ABANDON);

        if (change.getStatus() != Change.Status.NEW && change.getStatus() != Change.Status.ABANDONED) {
            logger.atSevere().log("qtcodereview: defer: change %s status wrong %s", change, change.getStatus());
            throw new ResourceConflictException("change is " + ChangeUtil.status(change));
        }

        QtChangeUpdateOp op = qtUpdateFactory.create(Change.Status.DEFERRED, "Deferred", input.message, null);
        try (BatchUpdate u =  updateFactory.create(dbProvider.get(), change.getProject(), rsrc.getUser(), TimeUtil.nowTs())) {
            u.addOp(rsrc.getId(), op).execute();
        }

        change = op.getChange();
        logger.atInfo().log("qtcodereview: deferred %s", change);

        return json.noOptions().format(change);
    }

    @Override
    public UiAction.Description getDescription(ChangeResource rsrc) {
        UiAction.Description description = new UiAction.Description()
                                                       .setLabel("Defer")
                                                       .setTitle("Defer the change")
                                                       .setVisible(false);

        Change change = rsrc.getChange();
        if (change.getStatus() != Change.Status.NEW && change.getStatus() != Change.Status.ABANDONED) {
            return description;
        }

        try {
            if (psUtil.isPatchSetLocked(rsrc.getNotes())) {
                return description;
            }
        } catch (OrmException | IOException e) {
            logger.atSevere().withCause(e).log("Failed to check if the current patch set of change %s is locked", change.getId());
            return description;
        }

        return description.setVisible(rsrc.permissions().testOrFalse(ChangePermission.ABANDON));
    }

}
