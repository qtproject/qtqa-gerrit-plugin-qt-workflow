//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gerrit.sshd.SshCommand;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gwtorm.server.OrmException;

import com.google.inject.Inject;
import com.google.inject.Provider;

import java.lang.NumberFormatException;
import java.util.List;

import org.kohsuke.args4j.Option;

@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
@CommandMetaData(name="change-status", description="Modify change status for admins. NOTE: This only affects change attribure. No real git merge or revert will be performed.")
class QtCommandAdminChangeStatus extends SshCommand {

    @Inject
    private Provider<ReviewDb> dbProvider;

    @Inject
    Provider<InternalChangeQuery> queryProvider;

    @Inject
    private BatchUpdate.Factory updateFactory;

    @Inject
    private QtChangeUpdateOp.Factory qtUpdateFactory;

    @Option(name = "--project", aliases = {"-p"},
        required = true, usage = "project name")
    private String project;

    @Option(name = "--change-id", aliases = {"-c"},
        required = true, usage = "change id (numeric, like in web url)")
    private String changeId;

    @Option(name = "--from",
        required = true, usage = "new, staged, integrating, merged, abandoned, deferred")
    private String fromStr;

    @Option(name = "--to",
        required = true, usage = "new, staged, integrating, merged, abandoned, deferred")
    private String toStr;

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    @Override
    protected void run() throws UnloggedFailure {
        logger.atInfo().log("qtcodereview: admin change-status start " + changeId);

        try {
            Change.Status to = toStatus(toStr);
            if (to == null) throw die("invalid to status");

            Change.Status from = toStatus(fromStr);
            if (from == null) throw die("invalid from status");

            Change.Id id = Change.Id.parse(changeId);
            if (id.get() == 0) throw die("invalid change-id");

            InternalChangeQuery query = queryProvider.get();
            List<ChangeData> list = query.byLegacyChangeId(id);
            if (list.isEmpty()) throw die("change not found");
            if (list.size() > 1) throw die("multiple changes found");
            ChangeData change = list.get(0);

            Project.NameKey projectKey = QtUtil.getProjectKey(project);
            QtChangeUpdateOp op = qtUpdateFactory.create(to, null, null, null, null);
            try (BatchUpdate u =  updateFactory.create(dbProvider.get(), projectKey, user, TimeUtil.nowTs())) {
                Change c = change.change();
                if (c.getStatus() == from) {
                    u.addOp(c.getId(), op);
                    u.execute();
                } else {
                    throw die("change status was not " + fromStr);
                }
            }
            logger.atInfo().log("qtcodereview: admin change-status done");
        } catch(NumberFormatException e) {
            throw die("change-id not numeric");
        } catch(OrmException e) {
            logger.atSevere().log("qtcodereview: change-status error %s", e.getMessage());
            throw die("Database query failed");
        } catch (UpdateException | RestApiException e) {
            logger.atSevere().log("qtcodereview: change-status error %s", e.getMessage());
            throw die("Database update failed");
        }
    }

    private Change.Status toStatus(String str) {
        switch (str) {
            case "new":
                return Change.Status.NEW;
            case "staged":
                return Change.Status.STAGED;
            case "integrating":
                return Change.Status.INTEGRATING;
            case "merged":
                return Change.Status.MERGED;
            case "abandoned":
                return Change.Status.ABANDONED;
            case "deferred":
                return Change.Status.DEFERRED;
            default:
                return null;
        }
    }

}
