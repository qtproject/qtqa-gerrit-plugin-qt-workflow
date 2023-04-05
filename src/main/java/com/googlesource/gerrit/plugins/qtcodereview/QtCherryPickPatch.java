//
// Copyright (C) 2021-23 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import static com.google.gerrit.server.project.ProjectCache.noSuchProject;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.CodeReviewCommit.CodeReviewRevWalk;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeUtil;
import com.google.gerrit.server.git.MergeUtilFactory;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.submit.IntegrationConflictException;
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
  private final MergeUtilFactory mergeUtilFactory;
  private final ProjectCache projectCache;
  private final QtChangeUpdateOp.Factory qtUpdateFactory;

  @Inject
  QtCherryPickPatch(
      BatchUpdate.Factory batchUpdateFactory,
      GitRepositoryManager gitManager,
      Provider<IdentifiedUser> user,
      MergeUtilFactory mergeUtilFactory,
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
      throws IntegrationConflictException {

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
            "cherrypick fast forward %s on top of %s", sourceId.name(), destId.name());
        return commitToCherryPick;
      }

      // Copy the committer, but change the date to now.
      PersonIdent committerIdent =
          new PersonIdent(commitToCherryPick.getCommitterIdent(), new Date());

      commitToCherryPick.setPatchsetId(changeData.currentPatchSet().id());
      commitToCherryPick.setNotes(changeData.notes());

      CodeReviewCommit cherryPickCommit;
      ProjectState projectState = projectCache.get(project).orElseThrow(noSuchProject(project));

      MergeUtil mergeUtil = mergeUtilFactory.create(projectState, true);
      if (commitToCherryPick.getParentCount() > 1) {
        // Merge commit cannot be cherrypicked
        logger.atInfo().log("merge commit detected %s", commitToCherryPick.name());

        if (commitToCherryPick.getParent(0).equals(baseCommit)) {
          // allow fast forward, when parent index 0 is correct
          logger.atInfo().log("merge commit fast forwarded");
          cherryPickCommit = commitToCherryPick;
        } else {
          logger.atInfo().log("merge of merge created");
          RevCommit commit =
              QtUtil.merge(
                  committerIdent, git, oi, revWalk, commitToCherryPick, baseCommit, null, true);
          cherryPickCommit = revWalk.parseCommit(commit);
          logger.atInfo().log(
              "commit %s merged as %s", commitToCherryPick.name(), cherryPickCommit.name());
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
        boolean patchSetNotChanged = cherryPickCommit.equals(commitToCherryPick);
        if (!patchSetNotChanged) {
          logger.atInfo().log(
              "commit %s cherrypicked as %s", commitToCherryPick.name(), cherryPickCommit.name());
          oi.flush();
        }
      }

      Timestamp commitTimestamp = new Timestamp(committerIdent.getWhen().getTime());
      BatchUpdate bu = batchUpdateFactory.create(project, identifiedUser, commitTimestamp.toInstant());
      bu.addOp(
          changeData.getId(),
          qtUpdateFactory.create(newStatus, null, defaultMessage, inputMessage, tag, null));
      bu.execute();
      logger.atInfo().log("cherrypick done for change %s", changeData.getId());
      return cherryPickCommit;
    } catch (Exception e) {
      throw new IntegrationConflictException("Reason: " + e.getMessage());
    }
  }
}
