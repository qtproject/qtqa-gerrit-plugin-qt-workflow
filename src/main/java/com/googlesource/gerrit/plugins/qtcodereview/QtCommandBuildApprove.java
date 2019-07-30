//
// Copyright (C) 2019 The Qt Company
//

package com.googlesource.gerrit.plugins.qtcodereview;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.change.PatchSetInserter;
import com.google.gerrit.server.extensions.events.ChangeMerged;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.mail.send.MergedSender;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.kohsuke.args4j.Option;

/**
 * A command to report pass or fail status for builds. When a build receives pass status, the branch
 * is updated with build ref and all open changes in the build are marked as merged. When a build
 * receives fail status, all change in the build are marked as new and they need to be staged again.
 *
 * <p>For example, how to approve a build $ ssh -p 29418 localhost gerrit-plugin-qt-workflow
 * staging-approve -p project -b master -i 123 -r=pass
 */
@CommandMetaData(
    name = "staging-approve",
    description =
        "Report pass or fail status for builds. If passed changed are merged into target branch.")
class QtCommandBuildApprove extends SshCommand {

  @Inject private PermissionBackend permissionBackend;

  @Inject private GitRepositoryManager gitManager;

  @Inject private MergedSender.Factory mergedSenderFactory;

  @Inject QtBuildFailedSender.Factory qtBuildFailedSenderFactory;

  @Inject private BatchUpdate.Factory updateFactory;

  @Inject private PatchSetInserter.Factory patchSetInserterFactory;

  @Inject private GitReferenceUpdated referenceUpdated;

  @Inject private ChangeMerged changeMerged;

  @Inject private QtUtil qtUtil;

  @Inject private QtChangeUpdateOp.Factory qtUpdateFactory;

  @Option(
      name = "--project",
      aliases = {"-p"},
      required = true,
      usage = "project name")
  private String project;

  @Option(
      name = "--build-id",
      aliases = {"-i"},
      required = true,
      usage = "build branch containing changes, e.g. refs/builds/123 or 123")
  private String buildBranch;

  @Option(
      name = "--result",
      aliases = {"-r"},
      required = true,
      usage = "pass or fail")
  private String result;

  @Option(
      name = "--message",
      aliases = {"-m"},
      metaVar = "-|MESSAGE",
      usage = "message added to all changes")
  private String message;

  @Option(
      name = "--branch",
      aliases = {"-b"},
      metaVar = "BRANCH",
      required = true,
      usage = "destination branch, e.g. refs/heads/master or just master")
  private String destBranch;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private Repository git;

  /** Parameter value for pass result. */
  private static final String PASS = "pass";
  /** Parameter value for fail result. */
  private static final String FAIL = "fail";
  /** Parameter value for stdin message. */
  private static final String STDIN_MESSAGE = "-";

  private Project.NameKey projectKey;
  private Branch.NameKey buildBranchKey;
  private Branch.NameKey destBranchKey;
  private Branch.NameKey stagingBranchKey;
  private Branch.NameKey destBranchShortKey;

  private List<Entry<ChangeData, RevCommit>> affectedChanges = null;

  @Override
  protected void run() throws UnloggedFailure {
    logger.atInfo().log(
        "qtcodereview: staging-approve -p %s -i %s -r %s -m %s -b %s",
        project, buildBranch, result, message, destBranch);

    readMessageParameter();

    projectKey = QtUtil.getProjectKey(project);
    buildBranchKey = QtUtil.getNameKeyLong(project, QtUtil.R_BUILDS, buildBranch);
    destBranchKey = QtUtil.getNameKeyLong(project, QtUtil.R_HEADS, destBranch);
    stagingBranchKey = QtUtil.getNameKeyLong(project, QtUtil.R_STAGING, destBranch);
    destBranchShortKey = QtUtil.getNameKeyShort(project, QtUtil.R_HEADS, destBranch);

    try {
      git = gitManager.openRepository(projectKey);

      // Check required permissions
      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(destBranchKey.get())
          .check(RefPermission.UPDATE);
      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(stagingBranchKey.get())
          .check(RefPermission.UPDATE);
      permissionBackend
          .user(user)
          .project(projectKey)
          .ref(buildBranchKey.get())
          .check(RefPermission.READ);

      if (git.resolve(destBranchKey.get()) == null) throw die("branch not found");
      if (git.resolve(buildBranchKey.get()) == null) throw die("build not found");

      // Initialize and populate open changes list.
      affectedChanges = qtUtil.listChangesNotMerged(git, buildBranchKey, destBranchKey);

      // Notify user that build did not have any open changes. The build has already been approved.
      if (affectedChanges.isEmpty()) {
        logger.atInfo().log(
            "qtcodereview: staging-approve build %s already in project %s branch %s",
            buildBranch, projectKey, destBranchKey);
        throw die("No open changes in the build branch");
      }

      if (result.toLowerCase().equals(PASS)) {
        approveBuildChanges();
      } else if (result.toLowerCase().equals(FAIL)) {
        rejectBuildChanges();
      } else {
        throw die("result argument accepts only value pass or fail.");
      }

    } catch (AuthException e) {
      throw die("not authorized");
    } catch (PermissionBackendException e) {
      throw die("git permission error");
    } catch (RepositoryNotFoundException e) {
      throw die("project not found");
    } catch (IOException e) {
      throw die(e.getMessage());
    } catch (QtUtil.BranchNotFoundException e) {
      throw die("invalid branch " + e.getMessage());
    } catch (NoSuchRefException e) {
      throw die("invalid reference " + e.getMessage());
    } catch (UpdateException | RestApiException | ConfigInvalidException e) {
      logger.atSevere().log("qtcodereview: staging-napprove failed to update change status %s", e);
      throw die("Failed to update change status");
    } catch (QtUtil.MergeConflictException e) {
      String msg = String.format("Merge build %s to branch %s failed", buildBranch, destBranchKey);
      logger.atSevere().log("qtcodereview: %s", msg);
      throw die(
          String.format("Merge conflict! build branch %s into %s failed", buildBranch, destBranch));
    } finally {
      if (git != null) git.close();
    }
  }

  private void approveBuildChanges()
      throws QtUtil.MergeConflictException, NoSuchRefException, IOException, UpdateException,
          RestApiException, ConfigInvalidException {
    if (message == null) message = String.format("Change merged into branch %s", destBranchKey);

    ObjectId oldId = git.resolve(destBranchKey.get());

    Result result =
        QtUtil.mergeBranches(user.asIdentifiedUser(), git, buildBranchKey, destBranchKey);

    if (result != Result.FAST_FORWARD) {
      message =
          "Branch update failed, changed back to NEW. Either the destination branch was changed externally, or this is an issue in the Qt plugin.";
      rejectBuildChanges();
      return;
    }

    updateChanges(
        affectedChanges, Change.Status.MERGED, null, message, ChangeMessagesUtil.TAG_MERGED, true);

    logger.atInfo().log(
        "qtcodereview: staging-approve build %s merged into branch %s", buildBranch, destBranchKey);

    ObjectId newId = git.resolve(destBranchKey.get());
    // send ref updated event only if there are changes to build
    if (!newId.equals(oldId)) {
      referenceUpdated.fire(
          projectKey, destBranchKey.get(), oldId, newId, user.asIdentifiedUser().state());
    }
  }

  private void rejectBuildChanges()
      throws QtUtil.MergeConflictException, UpdateException, RestApiException, IOException,
          ConfigInvalidException {
    if (message == null) message = String.format("Change rejected for branch %s", destBranchKey);

    updateChanges(
        affectedChanges,
        Change.Status.NEW,
        Change.Status.INTEGRATING,
        message,
        ChangeMessagesUtil.TAG_REVERT,
        false);

    // need to rebuild the staging ref because the reject changes need to be removed from there
    qtUtil.rebuildStagingBranch(
        git, user.asIdentifiedUser(), projectKey, stagingBranchKey, destBranchShortKey);

    logger.atInfo().log(
        "qtcodereview: staging-approve build %s rejected for branch %s",
        buildBranch, destBranchKey);
  }

  private void updateChanges(
      List<Entry<ChangeData, RevCommit>> list,
      Change.Status status,
      Change.Status oldStatus,
      String changeMessage,
      String tag,
      Boolean passed)
      throws UpdateException, RestApiException, IOException, ConfigInvalidException {

    List<Entry<ChangeData, RevCommit>> emailingList =
        new ArrayList<Map.Entry<ChangeData, RevCommit>>();

    // do the db update
    QtChangeUpdateOp op = qtUpdateFactory.create(status, oldStatus, changeMessage, null, tag, null);
    try (BatchUpdate u = updateFactory.create(projectKey, user, TimeUtil.nowTs())) {
      for (Entry<ChangeData, RevCommit> item : list) {
        ChangeData cd = item.getKey();
        Change change = cd.change();
        if ((oldStatus == null || change.getStatus() == oldStatus)
            && change.getStatus() != Change.Status.MERGED) {
          if (status == Change.Status.MERGED) {
            ObjectId obj = git.resolve(cd.currentPatchSet().getRevision().get());
            CodeReviewCommit currCommit = new CodeReviewCommit(obj);
            currCommit.setPatchsetId(cd.currentPatchSet().getId());
            CodeReviewCommit newCommit = new CodeReviewCommit(item.getValue());
            Change.Id changeId = insertPatchSet(u, git, cd.notes(), newCommit);
            if (!changeId.equals(cd.getId())) {
              logger.atWarning().log(
                  "staging-approve wrong changeId for new patchSet %s != %s", changeId, cd.getId());
            }
            u.addOp(
                changeId,
                qtUpdateFactory.create(status, oldStatus, changeMessage, null, tag, currCommit));
          } else {
            u.addOp(change.getId(), op);
          }
          emailingList.add(item);
        }
      }
      u.execute();
    }

    // do rest
    for (Entry<ChangeData, RevCommit> item : emailingList) {
      ChangeData cd = item.getKey();
      Change change = cd.change();
      if (passed) {
        sendMergeEvent(cd);
        sendMergedEmail(change.getId());
        logger.atInfo().log(
            "qtcodereview: staging-approve     change %s merged into %s", change, destBranchKey);
      } else {
        sendBuildFailedEmail(change.getId());
        logger.atInfo().log(
            "qtcodereview: staging-approve     change %s rejected for %s", change, destBranchKey);
      }
    }
  }

  private Change.Id insertPatchSet(
      BatchUpdate bu, Repository git, ChangeNotes destNotes, CodeReviewCommit cherryPickCommit)
      throws IOException, BadRequestException, ConfigInvalidException {
    Change destChange = destNotes.getChange();
    PatchSet.Id psId = ChangeUtil.nextPatchSetId(git, destChange.currentPatchSetId());
    PatchSetInserter inserter = patchSetInserterFactory.create(destNotes, psId, cherryPickCommit);
    inserter.setSendEmail(false).setAllowClosed(true);
    // .setCopyApprovals(true) doesn't work, so copying done in QtChangeUpdateOp
    bu.addOp(destChange.getId(), inserter);
    return destChange.getId();
  }

  private void sendMergeEvent(ChangeData changeData) {
    Timestamp ts = TimeUtil.nowTs();

    PatchSet ps = changeData.currentPatchSet();
    changeMerged.fire(
        changeData.change(), ps, user.asIdentifiedUser().state(), ps.getRevision().get(), ts);

    // logger.atInfo().log("qtcodereview: staging-approve sending merge event failed for %s",
    //                     changeData.change());
  }

  private void readMessageParameter() throws UnloggedFailure {
    if (message == null) return;

    try {
      // User will submit message through stdin.
      if (message.equals(STDIN_MESSAGE)) {
        // Clear stdin indicator.
        message = "";

        // Read message from stdin.
        BufferedReader stdin = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = stdin.readLine()) != null) {
          message += line + "\n";
        }
      }
    } catch (IOException e) {
      throw new UnloggedFailure(1, "fatal: " + e.getMessage(), e);
    }
  }

  private void sendMergedEmail(Change.Id changeId) {
    try {
      MergedSender mcm = mergedSenderFactory.create(projectKey, changeId);
      mcm.setFrom(user.getAccountId());
      mcm.send();
    } catch (Exception e) {
      logger.atWarning().log(
          "qtcodereview: staging-approve Merged notification not sent for %s %s", changeId, e);
    }
  }

  private void sendBuildFailedEmail(Change.Id changeId) {
    try {
      QtBuildFailedSender cm = qtBuildFailedSenderFactory.create(projectKey, changeId);
      cm.setFrom(user.getAccountId());
      cm.setChangeMessage(message, TimeUtil.nowTs());
      cm.send();
    } catch (Exception e) {
      logger.atWarning().log(
          "qtcodereview: staging-approve Build Failed not sent notification for %s %s",
          changeId, e);
    }
  }
}
