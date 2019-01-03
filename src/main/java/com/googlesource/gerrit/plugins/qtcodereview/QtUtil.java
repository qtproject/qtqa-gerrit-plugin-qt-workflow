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
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.submit.IntegrationException;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/**
 * Utility methods for working with git and database.
 */
@Singleton
public class QtUtil {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    public static final String R_HEADS = "refs/heads/";
    public static final String R_STAGING = "refs/staging/";

    private final Provider<InternalChangeQuery> queryProvider;
    private final GitReferenceUpdated referenceUpdated;
    private final QtCherryPickPatch qtCherryPickPatch;

    @Inject
    QtUtil(Provider<InternalChangeQuery> queryProvider,
           GitReferenceUpdated referenceUpdated,
           QtCherryPickPatch qtCherryPickPatch) {
        this.queryProvider = queryProvider;
        this.referenceUpdated = referenceUpdated;
        this.qtCherryPickPatch = qtCherryPickPatch;
    }

    public static class MergeConflictException extends Exception {
        private static final long serialVersionUID = 1L;
        public MergeConflictException(final String message) {
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

    private ObjectId pickChangestoStagingRef(Repository git,
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
                                                      null  // tag
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
        try {
            ObjectId oldStageRefObjId = git.resolve(stagingBranchKey.get());
            ObjectId branchObjId = git.resolve(destBranchShortKey.get());

            InternalChangeQuery query = queryProvider.get();
            List<ChangeData> changes_integrating = query.byBranchStatus(destBranchShortKey, Change.Status.INTEGRATING);

            query = queryProvider.get();
            List<ChangeData> changes_staged = query.byBranchStatus(destBranchShortKey, Change.Status.STAGED);

            List<ChangeData> changes_allowed = new ArrayList<ChangeData>();
            changes_allowed.addAll(changes_integrating);
            changes_allowed.addAll(changes_staged);
            List<ChangeData> changes = arrangeOrderLikeInRef(git, oldStageRefObjId, branchObjId, changes_allowed);

            logger.atInfo().log("qtcodereview: rebuild reset %s back to %s", stagingBranchKey, destBranchShortKey);
            Result result = QtUtil.createStagingBranch(git, destBranchShortKey);
            if (result == null) throw new NoSuchRefException("Cannot create staging ref: " + stagingBranchKey.get());
            logger.atInfo().log("qtcodereview: rebuild reset result %s",result);

            ObjectId newId = pickChangestoStagingRef(git,
                                                     projectKey,
                                                     changes,
                                                     git.resolve(stagingBranchKey.get()));

            RefUpdate refUpdate = git.updateRef(stagingBranchKey.get());
            refUpdate.setNewObjectId(newId);
            refUpdate.update();

            // send ref updated event only if it changed
            if (!newId.equals(oldStageRefObjId)) {
                referenceUpdated.fire(projectKey, stagingBranchKey.get(), oldStageRefObjId, newId, user.state());
            }

        } catch (OrmException e) {
            logger.atSevere().log("qtcodereview: rebuild %s failed. Failed to access database %s", stagingBranchKey, e);
            throw new MergeConflictException("fatal: Failed to access database");
        } catch (IOException e) {
            logger.atSevere().log("qtcodereview: rebuild %s failed. IOException %s", stagingBranchKey, e);
            throw new MergeConflictException("fatal: IOException");
        } catch (NoSuchRefException e) {
            logger.atSevere().log("qtcodereview: rebuild %s failed. No such ref %s", stagingBranchKey, e);
            throw new MergeConflictException("fatal: NoSuchRefException");
        } catch(IntegrationException e) {
            logger.atSevere().log("qtcodereview: rebuild %s failed. IntegrationException %s", stagingBranchKey, e);
            throw new MergeConflictException("fatal: IntegrationException");
        }
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

}
