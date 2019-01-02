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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.inject.Singleton;

import org.eclipse.jgit.lib.CommitBuilder;
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


/**
 * Utility methods for working with git and database.
 */
@Singleton
public class QtUtil {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    public static final String R_HEADS = "refs/heads/";
    public static final String R_STAGING = "refs/staging/";

    public static class MergeConflictException extends Exception {
        private static final long serialVersionUID = 1L;
        public MergeConflictException(final String message) {
            super(message);
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
