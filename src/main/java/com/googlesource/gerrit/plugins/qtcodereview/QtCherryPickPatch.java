//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.MergeConflictException;
import com.google.gerrit.extensions.restapi.MethodNotAllowedException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.PatchSetInserter;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.CodeReviewCommit.CodeReviewRevWalk;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeUtil;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.submit.IntegrationException;
import com.google.gerrit.server.submit.MergeIdenticalTreeException;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

@Singleton
public class QtCherryPickPatch {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private final Provider<ReviewDb> dbProvider;
    private final BatchUpdate.Factory batchUpdateFactory;
    private final GitRepositoryManager gitManager;
    private final Provider<IdentifiedUser> user;
    private final PatchSetInserter.Factory patchSetInserterFactory;
    private final MergeUtil.Factory mergeUtilFactory;
    private final ProjectCache projectCache;
    private final QtChangeUpdateOp.Factory qtUpdateFactory;

    @Inject
    QtCherryPickPatch(Provider<ReviewDb> dbProvider,
                      BatchUpdate.Factory batchUpdateFactory,
                      GitRepositoryManager gitManager,
                      Provider<IdentifiedUser> user,
                      PatchSetInserter.Factory patchSetInserterFactory,
                      MergeUtil.Factory mergeUtilFactory,
                      ProjectCache projectCache,
                      QtChangeUpdateOp.Factory qtUpdateFactory) {
        this.dbProvider = dbProvider;
        this.batchUpdateFactory = batchUpdateFactory;
        this.gitManager = gitManager;
        this.user = user;
        this.patchSetInserterFactory = patchSetInserterFactory;
        this.mergeUtilFactory = mergeUtilFactory;
        this.projectCache = projectCache;
        this.qtUpdateFactory = qtUpdateFactory;
    }

    public CodeReviewCommit cherryPickPatch(ChangeData changeData,
                                            Project.NameKey project,
                                            ObjectId sourceId,
                                            ObjectId destId,
                                            boolean allowFastForward,
                                            Change.Status newStatus,
                                            String defaultMessage,
                                            String inputMessage,
                                            String tag)
                                            throws IntegrationException {

        IdentifiedUser identifiedUser = user.get();
        try (Repository git = gitManager.openRepository(project);
             ObjectInserter oi = git.newObjectInserter();
             ObjectReader reader = oi.newReader();
             CodeReviewRevWalk revWalk = CodeReviewCommit.newRevWalk(reader)) {

            if (!git.hasObject(sourceId)) throw new NoSuchRefException("Invalid source objectId: " + sourceId);
            if (!git.hasObject(destId)) throw new NoSuchRefException("Invalid destination objectid: " + destId);

            RevCommit baseCommit = revWalk.parseCommit(destId);
            CodeReviewCommit commitToCherryPick = revWalk.parseCommit(sourceId);

            List parents = Arrays.asList(commitToCherryPick.getParents());
            if (allowFastForward == true && parents.contains(baseCommit) && commitToCherryPick.getParentCount() < 2) {
                logger.atInfo().log("qtcodereview: cherrypick fast forward %s on top of %s", sourceId, destId);
                return commitToCherryPick;
            }

            Timestamp now = TimeUtil.nowTs();
            PersonIdent committerIdent = commitToCherryPick.getCommitterIdent();

            commitToCherryPick.setPatchsetId(changeData.currentPatchSet().getId());
            commitToCherryPick.setNotes(changeData.notes());

            CodeReviewCommit cherryPickCommit;
            boolean mergeCommit = false;

            ProjectState projectState = projectCache.checkedGet(project);
            if (projectState == null) throw new NoSuchProjectException(project);

            MergeUtil mergeUtil = mergeUtilFactory.create(projectState, true);
            if (commitToCherryPick.getParentCount() > 1) {
                // Merge commit cannot be cherrypicked
                logger.atInfo().log("qtcodereview: merge commit detected %s", commitToCherryPick);
                mergeCommit = true;
                RevCommit commit = QtUtil.merge(committerIdent,
                                                git, oi,
                                                revWalk,
                                                commitToCherryPick,
                                                baseCommit,
                                                true /* merge always */);
                cherryPickCommit = revWalk.parseCommit(commit);
            } else {
                String commitMessage = mergeUtil.createCommitMessageOnSubmit(commitToCherryPick, baseCommit);
                commitMessage += " "; // This ensures unique SHA1 is generated, otherwise old is reused
                cherryPickCommit = mergeUtil.createCherryPickFromCommit(oi,
                                                                        git.getConfig(),
                                                                        baseCommit,
                                                                        commitToCherryPick,
                                                                        committerIdent,
                                                                        commitMessage,
                                                                        revWalk,
                                                                        0,
                                                                        true,   // ignoreIdenticalTree
                                                                        false); // allowConflicts
            }

            boolean patchSetNotChanged = cherryPickCommit.equals(commitToCherryPick);
            if (!patchSetNotChanged) {
                logger.atInfo().log("qtcodereview: new patch %s -> %s", commitToCherryPick, cherryPickCommit);
                oi.flush();
            }
            BatchUpdate bu = batchUpdateFactory.create(dbProvider.get(), project, identifiedUser, now);
            bu.setRepository(git, revWalk, oi);
            if (!patchSetNotChanged && !mergeCommit) {
                Change.Id changeId = insertPatchSet(bu, git, changeData.notes(), cherryPickCommit);
                bu.addOp(changeData.getId(), qtUpdateFactory.create(newStatus,
                                                                    defaultMessage,
                                                                    inputMessage,
                                                                    tag,
                                                                    commitToCherryPick));
                logger.atInfo().log("qtcodereview: cherrypick new patch %s for %s", cherryPickCommit.toObjectId(), changeId);
            } else {
                bu.addOp(changeData.getId(), qtUpdateFactory.create(newStatus,
                                                                    defaultMessage,
                                                                    inputMessage,
                                                                    tag,
                                                                    null));
            }

            bu.execute();
            logger.atInfo().log("qtcodereview: cherrypick done %s", changeData.getId());
            return cherryPickCommit;
        } catch (Exception e) {
            throw new IntegrationException("Cherry pick failed: " + e.getMessage());
        }
    }

    private Change.Id insertPatchSet(BatchUpdate bu,
                                     Repository git,
                                     ChangeNotes destNotes,
                                     CodeReviewCommit cherryPickCommit)
                                     throws IOException, OrmException, BadRequestException, ConfigInvalidException {
        Change destChange = destNotes.getChange();
        PatchSet.Id psId = ChangeUtil.nextPatchSetId(git, destChange.currentPatchSetId());
        PatchSetInserter inserter = patchSetInserterFactory.create(destNotes, psId, cherryPickCommit);
        inserter.setNotify(NotifyHandling.NONE)
                .setAllowClosed(true);
                // .setCopyApprovals(true) doesn't work, so copying done in QtChangeUpdateOp
        bu.addOp(destChange.getId(), inserter);
        return destChange.getId();
    }

}
