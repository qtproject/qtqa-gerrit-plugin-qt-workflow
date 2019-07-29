// Copyright (C) 2011 The Android Open Source Project
// Copyright (C) 2014 Digia Plc and/or its subsidiary(-ies).
// Copyright (C) 2019 The Qt Company
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

import com.google.gerrit.common.FooterConstants;
import com.google.common.collect.Lists;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.submit.IntegrationException;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;


/**
 * Utility methods for working with git and database.
 */
@Singleton
public class QtUtil {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    public static final String R_HEADS = "refs/heads/";
    public static final String R_STAGING = "refs/staging/";
    public static final String R_BUILDS = "refs/builds/";

    private final Provider<ReviewDb> dbProvider;
    private final Provider<InternalChangeQuery> queryProvider;
    private final GitReferenceUpdated referenceUpdated;
    private final BatchUpdate.Factory updateFactory;
    private final QtCherryPickPatch qtCherryPickPatch;
    private final QtChangeUpdateOp.Factory qtUpdateFactory;

    @Inject
    QtUtil(Provider<ReviewDb> dbProvider,
           Provider<InternalChangeQuery> queryProvider,
           GitReferenceUpdated referenceUpdated,
           BatchUpdate.Factory updateFactory,
           QtCherryPickPatch qtCherryPickPatch,
           QtChangeUpdateOp.Factory qtUpdateFactory) {
        this.dbProvider = dbProvider;
        this.queryProvider = queryProvider;
        this.referenceUpdated = referenceUpdated;
        this.updateFactory = updateFactory;
        this.qtCherryPickPatch = qtCherryPickPatch;
        this.qtUpdateFactory = qtUpdateFactory;
    }

    public static class MergeConflictException extends Exception {
        private static final long serialVersionUID = 1L;
        public MergeConflictException(final String message) {
            super(message);
        }
    }

    public static class BranchNotFoundException extends Exception {
        private static final long serialVersionUID = 1L;
        public BranchNotFoundException(final String message) {
            super(message);
        }
    }

    public static Project.NameKey getProjectKey(final String project) {
        String projectName = project;
        if (project.endsWith(Constants.DOT_GIT_EXT)) {
            projectName = project.substring(0, project.length() - Constants.DOT_GIT_EXT.length());
        }
        return new Project.NameKey(projectName);
    }

    /**
     * Creates a branch key including ref prefix.
     * @param project Project for the branch key.
     * @param prefix Expected prefix.
     * @param branch Branch name with or without prefix.
     * @return Branch name key with prefix.
     */
    public static Branch.NameKey getNameKeyLong(final String project,
                                                final String prefix,
                                                final String branch) {
        final Project.NameKey projectKey = getProjectKey(project);
        if (branch.startsWith(prefix)) {
            return new Branch.NameKey(projectKey, branch);
        } else {
            return new Branch.NameKey(projectKey, prefix + branch);
        }
    }

    /**
     * Creates a branch key without any prefix.
     * @param project Project for the branch key.
     * @param prefix Prefix to remove.
     * @param branch Branch name with or without prefix.
     * @return Branch name key without prefix.
     */
    public static Branch.NameKey getNameKeyShort(final String project,
                                                 final String prefix,
                                                 final String branch) {
        final Project.NameKey projectKey = getProjectKey(project);
        if (branch.startsWith(prefix)) {
            return new Branch.NameKey(projectKey, branch.substring(prefix.length()));
        } else {
            return new Branch.NameKey(projectKey, branch);
        }
    }

    public static boolean branchExists(Repository git, final Branch.NameKey branch)
                                       throws IOException {
        return git.getRefDatabase().getRef(branch.get()) != null;
    }

    /**
     * Gets a staging branch for a branch.
     * @param branch Branch under refs/heads. E.g. refs/heads/master. Can be short
     *        name.
     * @return Matching staging branch. E.g. refs/staging/master
     */
    public static Branch.NameKey getStagingBranch(final Branch.NameKey branch) {
        return getBranchWithNewPrefix(branch, R_HEADS, R_STAGING);
    }

    private static Branch.NameKey getBranchWithNewPrefix(final Branch.NameKey branch,
                                                         final String oldPrefix,
                                                         final String newPrefix) {
        final String ref = branch.get();

        if (ref.startsWith(oldPrefix)) {
            // Create new ref replacing the old prefix with new.
            return new Branch.NameKey(branch.getParentKey(), newPrefix + ref.substring(oldPrefix.length()));
        }
        // Treat the ref as short name.
        return new Branch.NameKey(branch.getParentKey(), newPrefix + ref);
    }

    public static Result createStagingBranch(Repository git,
                                             final Branch.NameKey sourceBranch) {
        try {
            final String sourceBranchName;
            if (sourceBranch.get().startsWith(R_HEADS)) {
                sourceBranchName = sourceBranch.get();
            } else {
                sourceBranchName = R_HEADS + sourceBranch.get();
            }

            final String stagingBranch = R_STAGING + sourceBranch.getShortName();

            return updateRef(git, stagingBranch, sourceBranchName, true);
        } catch (NoSuchRefException | IOException e ) {
            return null;
        }
    }

    /**
     * Creates a build ref. Build refs are stored under refs/builds.
     *
     * @param git Git repository.
     * @param stagingBranch Staging branch to create the build ref from. Can be
     *        short name.
     * @param newBranch Build ref name, under refs/builds. Can be short name.
     * @return
     * @throws IOException
     * @throws NoSuchRefException
     */
    public Result createBuildRef(Repository git,
                                 IdentifiedUser user,
                                 final Project.NameKey projectKey,
                                 final Branch.NameKey stagingBranch,
                                 final Branch.NameKey newBranch)
                                 throws IOException, NoSuchRefException {
        final String stagingBranchName;
        if (stagingBranch.get().startsWith(R_STAGING)) {
            stagingBranchName = stagingBranch.get();
        } else {
            stagingBranchName = R_STAGING + stagingBranch.get();
        }

        final String buildBranchName;
        if (newBranch.get().startsWith(R_BUILDS)) {
            buildBranchName = newBranch.get();
        } else {
            buildBranchName = R_BUILDS + newBranch.get();
        }

        Ref sourceRef = git.getRefDatabase().getRef(stagingBranchName);
        if (sourceRef == null) { throw new NoSuchRefException(stagingBranchName); }

        RefUpdate refUpdate = git.updateRef(buildBranchName);
        refUpdate.setNewObjectId(sourceRef.getObjectId());
        refUpdate.setForceUpdate(false);
        RefUpdate.Result result = refUpdate.update();

        // send ref created event
        referenceUpdated.fire(projectKey, refUpdate, ReceiveCommand.Type.CREATE, user.state());

        return result;
    }

    private static Result updateRef(Repository git,
                                    final String ref,
                                    final String newValue,
                                    final boolean force)
                                    throws IOException, NoSuchRefException {
        Ref sourceRef = git.getRefDatabase().getRef(newValue);
        if (sourceRef == null) {
            throw new NoSuchRefException(newValue);
        }

        return updateRef(git, ref, sourceRef.getObjectId(), force);
    }

    public static Result updateRef(Repository git,
                                   final String ref,
                                   final ObjectId id,
                                   final boolean force)
                                   throws IOException, NoSuchRefException {
        RefUpdate refUpdate = git.updateRef(ref);
        refUpdate.setNewObjectId(id);
        refUpdate.setForceUpdate(force);
        RefUpdate.Result result = refUpdate.update();
        return result;
    }

    private String getChangeId(RevCommit commit) {
        List<String> changeIds = commit.getFooterLines(FooterConstants.CHANGE_ID);
        String changeId = null;
        if (!changeIds.isEmpty()) changeId = changeIds.get(0);
        return changeId;
    }

    private ChangeData findChangeFromList(String changeId, List<ChangeData> changes)
                                          throws OrmException {
        for (ChangeData item : changes) {
            if (item.change().getKey().get().equals(changeId)) return item;
        }
        return null;
    }

    private List<ChangeData> arrangeOrderLikeInRef(Repository git,
                                                   ObjectId refObj,
                                                   ObjectId tipObj,
                                                   List<ChangeData> changeList)
                                                   throws MissingObjectException, OrmException,
                                                          IOException {
        List<ChangeData> results = new ArrayList<ChangeData>();
        if (refObj.equals(tipObj)) return results;

        RevWalk revWalk = new RevWalk(git);
        RevCommit commit = revWalk.parseCommit(refObj);
        int count = 0;
        do {
            count++;
            String changeId = getChangeId(commit);

            if (commit.getParentCount() == 0) {
                commit = null; // something is going wrong, just exit
            } else {
                if (changeId == null && commit.getParentCount() > 1) {
                    changeId = getChangeId(revWalk.parseCommit(commit.getParent(1)));
                }
                ChangeData change = findChangeFromList(changeId, changeList);
                if (change != null) results.add(0, change);

                commit = revWalk.parseCommit(commit.getParent(0));
            }
        } while (commit != null && !commit.equals(tipObj) && count < 100);

        if (count == 100) return null;
        return results;
    }

    private ObjectId pickChangesToStagingRef(Repository git,
                                             final Project.NameKey projectKey,
                                             List<ChangeData> changes,
                                             ObjectId tipObj)
                                             throws OrmException, IOException, IntegrationException {
        ObjectId newId = tipObj;
        for (ChangeData item : changes) {
            Change change = item.change();
            logger.atInfo().log("qtcodereview: rebuilding add %s", change);
            PatchSet p = item.currentPatchSet();
            ObjectId srcId = git.resolve(p.getRevision().get());
            newId = qtCherryPickPatch.cherryPickPatch(item,
                                                      projectKey,
                                                      srcId,
                                                      newId,
                                                      true, // allowFastForward
                                                      null, // newStatus
                                                      null, // defaultMessage
                                                      null, // inputMessage
                                                      "autogenerated:ci"  // tag
                                                      ).toObjectId();
        }
        return newId;
    }

    public void rebuildStagingBranch(Repository git,
                                     IdentifiedUser user,
                                     final Project.NameKey projectKey,
                                     final Branch.NameKey stagingBranchKey,
                                     final Branch.NameKey destBranchShortKey)
                                     throws MergeConflictException {
         InternalChangeQuery query = null;
         List<ChangeData> changes_integrating = null;
         List<ChangeData> changes_staged = null;
         ObjectId oldStageRefObjId = null;
         ObjectId branchObjId = null;
         ObjectId newStageRefObjId = null;
         ObjectId newObjId = null;
         String branchName = null;

         try {
             branchName = stagingBranchKey.get();
             oldStageRefObjId = git.resolve(branchName);
             branchObjId = git.resolve(destBranchShortKey.get());

             query = queryProvider.get();
             List<ChangeData> unsorted_list = query.byBranchStatus(destBranchShortKey, Change.Status.INTEGRATING);
             changes_integrating = arrangeOrderLikeInRef(git, oldStageRefObjId, branchObjId, unsorted_list);

             query = queryProvider.get();
             unsorted_list = query.byBranchStatus(destBranchShortKey, Change.Status.STAGED);
             changes_staged = arrangeOrderLikeInRef(git, oldStageRefObjId, branchObjId, unsorted_list);
         } catch (OrmException e) {
             logger.atSevere().log("qtcodereview: rebuild staging ref %s failed. Failed to access database %s",
                                    stagingBranchKey, e);
             throw new MergeConflictException("fatal: Failed to access database");
         } catch (IOException e) {
             logger.atSevere().log("qtcodereview: rebuild staging ref %s db failed. IOException %s",
                                    stagingBranchKey, e);
             throw new MergeConflictException("fatal: IOException");
         }

         try {
             logger.atInfo().log("qtcodereview: rebuild staging ref reset %s back to %s",
                                 stagingBranchKey, destBranchShortKey);
             Result result = QtUtil.createStagingBranch(git, destBranchShortKey);
             if (result == null) throw new NoSuchRefException("Cannot create staging ref: " + branchName);
             logger.atInfo().log("qtcodereview: rebuild staging ref reset result %s", result);
             newStageRefObjId = git.resolve(branchName);
         } catch (NoSuchRefException e) {
             logger.atSevere().log("qtcodereview: rebuild staging ref reset %s failed. No such ref %s",
                                    stagingBranchKey, e);
             throw new MergeConflictException("fatal: NoSuchRefException");
         } catch (IOException e) {
             logger.atSevere().log("qtcodereview: rebuild staging ref reset %s failed. IOException %s",
                                    stagingBranchKey, e);
             throw new MergeConflictException("fatal: IOException");
         }

         try {
             newObjId = pickChangesToStagingRef(git, projectKey, changes_integrating, newStageRefObjId);
             newStageRefObjId = newObjId;
         } catch(Exception e) {
             logger.atSevere().log("qtcodereview: rebuild staging ref %s. failed to cherry pick integrating changes %s",
                                   stagingBranchKey, e);
             newObjId = null;
         }

         if (newObjId != null) {
             try {
                 newObjId = pickChangesToStagingRef(git, projectKey, changes_staged, newObjId);
                 newStageRefObjId = newObjId;
             } catch(Exception e) {
                 newObjId = null;
                 logger.atInfo().log("qtcodereview: rebuild staging ref %s merge conflict", stagingBranchKey);
                 String message = "Merge conflict in staging branch. Status changed back to new. Please stage again.";
                 QtChangeUpdateOp op = qtUpdateFactory.create(Change.Status.NEW, Change.Status.STAGED, message, null, null, null);
                 try (BatchUpdate u = updateFactory.create(dbProvider.get(), projectKey, user, TimeUtil.nowTs())) {
                     for (ChangeData item: changes_staged) {
                         Change change = item.change();
                         logger.atInfo().log("qtcodereview: staging ref rebuild merge conflict. Change %s back to NEW", change);
                         u.addOp(change.getId(), op);
                     }
                     u.execute();
                 } catch (OrmException ex) {
                     logger.atSevere().log("qtcodereview: staging ref rebuild. Failed to access database %s", ex);
                 } catch (UpdateException | RestApiException ex) {
                     logger.atSevere().log("qtcodereview: staging ref rebuild. Failed to update change status %s", ex);
                 }
             }
         }

         try {
             RefUpdate refUpdate = git.updateRef(branchName);
             refUpdate.setNewObjectId(newStageRefObjId);
             refUpdate.update();

             // send ref updated event only if it changed
             if (!newStageRefObjId.equals(oldStageRefObjId)) {
                 referenceUpdated.fire(projectKey, branchName, oldStageRefObjId,
                                       newStageRefObjId, user.state());
             }
         } catch (IOException e) {
             logger.atSevere().log("qtcodereview: rebuild %s failed to update ref %s", stagingBranchKey, e);
             throw new MergeConflictException("fatal: IOException");
         }
    }

    /**
     * Lists not merged changes between branches.
     * @param git jGit Repository. Must be open.
     * @param db ReviewDb of a Gerrit site.
     * @param branch Branch to search for the changes.
     * @param destination Destination branch for changes.
     * @return List of not merged changes.
     * @throws IOException Thrown by Repository or RevWalk if repository is not
     *         accessible.
     * @throws OrmException Thrown if ReviewDb is not accessible.
     */
    public List<Map.Entry<ChangeData,RevCommit>> listChangesNotMerged(Repository git,
                                                                      final Branch.NameKey branch,
                                                                      final Branch.NameKey destination)
                                                                      throws IOException, OrmException,
                                                                             BranchNotFoundException {

        List<Map.Entry<ChangeData, RevCommit>> result = new ArrayList<Map.Entry<ChangeData, RevCommit>>();
        RevWalk revWalk = new RevWalk(git);

        try {
            Ref ref = git.getRefDatabase().getRef(branch.get());
            if (ref == null) throw new BranchNotFoundException("No such branch: " + branch);
            Ref refDest = git.getRefDatabase().getRef(destination.get());
            if (refDest == null) throw new BranchNotFoundException("No such branch: " + destination);
            RevCommit firstCommit = revWalk.parseCommit(ref.getObjectId());
            revWalk.markStart(firstCommit);
            // Destination is the walker end point
            revWalk.markUninteresting(revWalk.parseCommit(refDest.getObjectId()));

            Iterator<RevCommit> i = revWalk.iterator();
            while (i.hasNext()) {
                RevCommit commit = i.next();
                List<ChangeData> changes = queryProvider.get().byBranchCommit(destination, commit.name());
                if (changes != null && !changes.isEmpty()) {
                    if (changes.size() > 1) logger.atWarning().log("qtcodereview: commit belongs to multiple changes: %s", commit.name());
                    ChangeData cd = changes.get(0);
                    result.add(new AbstractMap.SimpleEntry<ChangeData,RevCommit>(cd, commit));
                }
            }
        } finally {
            revWalk.dispose();
        }
        return result;
    }

    public static RevCommit merge(PersonIdent committerIdent,
                                  Repository git,
                                  ObjectInserter objInserter,
                                  RevWalk revWalk,
                                  RevCommit toMerge,
                                  RevCommit mergeTip,
                                  boolean mergeAlways)
                                  throws NoSuchRefException, IOException, MergeConflictException {

        if (revWalk.isMergedInto(toMerge, mergeTip)) {
            logger.atWarning().log("qtcodereview: commit %s already in %s", toMerge, mergeTip);
            return mergeTip; // already up to date
        }

        ThreeWayMerger merger = MergeStrategy.RESOLVE.newMerger(git, true);
        if (!merger.merge(mergeTip, toMerge)) {
            logger.atWarning().log("qtcodereview: merge conflict %s on top of %s", toMerge, mergeTip);
            throw new MergeConflictException("Merge conflict");
        }

        if (!mergeAlways && merger.getResultTreeId().equals(toMerge.getTree().toObjectId())) {
            // Just fast forward, note that this will bring in all dependencies from source
            logger.atInfo().log("qtcodereview: merge fast forward %s on top of %s", toMerge, mergeTip);
            return toMerge;
        }

        String message;
        try {
            message = revWalk.parseCommit(toMerge).getShortMessage();
        } catch (Exception e) {
            message = toMerge.toString();
        }
        message = "Merge \"" + message + "\"";

        final CommitBuilder mergeCommit = new CommitBuilder();
        mergeCommit.setTreeId(merger.getResultTreeId());
        mergeCommit.setParentIds(mergeTip, toMerge);
        mergeCommit.setAuthor(toMerge.getAuthorIdent());
        mergeCommit.setCommitter(committerIdent);
        mergeCommit.setMessage(message);

        return revWalk.parseCommit(objInserter.insert(mergeCommit));
    }

    public static RefUpdate.Result mergeBranches(IdentifiedUser user,
                                                 Repository git,
                                                 final Branch.NameKey branch,
                                                 final Branch.NameKey destination)
                                                 throws NoSuchRefException, IOException, MergeConflictException {

        ObjectId srcId = git.resolve(branch.get());
        if (srcId == null) throw new NoSuchRefException("Invalid Revision: " + branch);

        return mergeObjectToBranch(user, git, srcId, destination);
    }

    private static RefUpdate.Result mergeObjectToBranch(IdentifiedUser user,
                                                        Repository git,
                                                        ObjectId srcId,
                                                        final Branch.NameKey destination)
                                                        throws NoSuchRefException, IOException, MergeConflictException {

        Ref destRef = git.getRefDatabase().getRef(destination.get());
        if (destRef == null) throw new NoSuchRefException("No such branch: " + destination);

        ObjectId destId = git.resolve(destination.get());
        if (destId == null) throw new NoSuchRefException("Invalid Revision: " + destination);

        RevWalk revWalk = new RevWalk(git);
        try {

            ObjectInserter objInserter = git.newObjectInserter();
            RevCommit mergeTip = revWalk.lookupCommit(destId);
            RevCommit toMerge = revWalk.lookupCommit(srcId);
            PersonIdent committer = user.newCommitterIdent(new Timestamp(System.currentTimeMillis()), TimeZone.getDefault());

            RevCommit mergeCommit = merge(committer,
                                          git,
                                          objInserter,
                                          revWalk,
                                          toMerge,
                                          mergeTip,
                                          false);
            objInserter.flush();
            logger.atInfo().log("qtcodereview: merge commit for %s added to %s", srcId, destination);

            RefUpdate refUpdate = git.updateRef(destination.get());
            refUpdate.setNewObjectId(mergeCommit);
            return refUpdate.update();
        } finally {
            revWalk.dispose();
        }
    }

}
