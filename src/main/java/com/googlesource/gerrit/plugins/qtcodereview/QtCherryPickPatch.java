//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.CodeReviewCommit.CodeReviewRevWalk;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeUtil;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.submit.IntegrationException;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

@Singleton
public class QtCherryPickPatch {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final BatchUpdate.Factory batchUpdateFactory;
  private final GitRepositoryManager gitManager;
  private final Provider<IdentifiedUser> user;
  private final MergeUtil.Factory mergeUtilFactory;
  private final ProjectCache projectCache;
  private final QtChangeUpdateOp.Factory qtUpdateFactory;

  @Inject
  QtCherryPickPatch(
      BatchUpdate.Factory batchUpdateFactory,
      GitRepositoryManager gitManager,
      Provider<IdentifiedUser> user,
      MergeUtil.Factory mergeUtilFactory,
      ProjectCache projectCache,
      QtChangeUpdateOp.Factory qtUpdateFactory) {
    this.batchUpdateFactory = batchUpdateFactory;
    this.gitManager = gitManager;
    this.user = user;
    this.mergeUtilFactory = mergeUtilFactory;
    this.projectCache = projectCache;
    this.qtUpdateFactory = qtUpdateFactory;
  }

  public CodeReviewCommit cherryPickPatch(
      ChangeData changeData,
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

      if (!git.hasObject(sourceId))
        throw new NoSuchRefException("Invalid source objectId: " + sourceId);
      if (!git.hasObject(destId))
        throw new NoSuchRefException("Invalid destination objectid: " + destId);

      RevCommit baseCommit = revWalk.parseCommit(destId);
      CodeReviewCommit commitToCherryPick = revWalk.parseCommit(sourceId);

      List parents = Arrays.asList(commitToCherryPick.getParents());
      if (allowFastForward == true
          && parents.contains(baseCommit)
          && commitToCherryPick.getParentCount() < 2) {
        logger.atInfo().log(
            "qtcodereview: cherrypick fast forward %s on top of %s", sourceId, destId);
        return commitToCherryPick;
      }

      // Copy the committer, but change the date to now.
      PersonIdent committerIdent =
          new PersonIdent(commitToCherryPick.getCommitterIdent(), new Date());

      commitToCherryPick.setPatchsetId(changeData.currentPatchSet().getId());
      commitToCherryPick.setNotes(changeData.notes());

      CodeReviewCommit cherryPickCommit;

      ProjectState projectState = projectCache.checkedGet(project);
      if (projectState == null) throw new NoSuchProjectException(project);

      MergeUtil mergeUtil = mergeUtilFactory.create(projectState, true);
      if (commitToCherryPick.getParentCount() > 1) {
        // Merge commit cannot be cherrypicked
        logger.atInfo().log("qtcodereview: merge commit detected %s", commitToCherryPick);

        if (commitToCherryPick.getParent(0).equals(baseCommit)) {
          // allow fast forward, when parent index 0 is correct
          logger.atInfo().log("qtcodereview: merge commit fast forwarded");
          cherryPickCommit = commitToCherryPick;
        } else {
          logger.atInfo().log("qtcodereview: merge of merge created");
          RevCommit commit =
              QtUtil.merge(committerIdent, git, oi, revWalk, commitToCherryPick, baseCommit, true);
          cherryPickCommit = revWalk.parseCommit(commit);
        }
      } else {
        String commitMessage =
            mergeUtil.createCommitMessageOnSubmit(commitToCherryPick, baseCommit);
        cherryPickCommit =
            mergeUtil.createCherryPickFromCommit(
                oi,
                git.getConfig(),
                baseCommit,
                commitToCherryPick,
                committerIdent,
                commitMessage,
                revWalk,
                0,
                true, // ignoreIdenticalTree
                false); // allowConflicts
      }

      boolean patchSetNotChanged = cherryPickCommit.equals(commitToCherryPick);
      if (!patchSetNotChanged) {
        logger.atInfo().log(
            "qtcodereview: %s cherrypicked as %s", commitToCherryPick, cherryPickCommit);
        oi.flush();
      }
      Timestamp commitTimestamp = new Timestamp(committerIdent.getWhen().getTime());
      BatchUpdate bu = batchUpdateFactory.create(project, identifiedUser, commitTimestamp);
      bu.addOp(
          changeData.getId(),
          qtUpdateFactory.create(newStatus, null, defaultMessage, inputMessage, tag, null));
      bu.execute();
      logger.atInfo().log("qtcodereview: cherrypick done %s", changeData.getId());
      return cherryPickCommit;
    } catch (Exception e) {
      throw new IntegrationException("Reason: " + e.getMessage());
    }
  }
}
