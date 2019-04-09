/**
 * Copyright (C) 2016 Matthieu Brouillard [http://oss.brouillard.fr/jgitver] (matthieu@brouillard.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.brouillard.oss.jgitver.impl;

import java.io.IOException;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.NameRevCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.DepthWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Allow to compute git depth, in term of commit distance between several commits.
 */
public interface DistanceCalculator {
    /**
     * Creates a reusable {@link DistanceCalculator} on the given repository for the given start commit.
     * @param start the commit where the computation will start
     * @param repository the repository on which to operate
     * @param maxDepth the maximum depth to which we accept to look at. If <= 0 then Integer.MAX_VALUE will be used.
     * @return a reusable {@link DistanceCalculator} object
     */
    static DistanceCalculator create(ObjectId start, Repository repository, int maxDepth) {
        return new RevWalkDistanceCalculator(start, repository, maxDepth > 0 ? maxDepth : Integer.MAX_VALUE);
    }

    /**
     * Creates a reusable {@link DistanceCalculator} on the given repository for the given start commit,
     * uses Integer.MAX_VALUE as the maximum depth distance.
     * @see #create(ObjectId, Repository, int)
     */
    static DistanceCalculator create(ObjectId start, Repository repository) {
        return create(start, repository, Integer.MAX_VALUE);
    }

    /**
     * Computes an eventual distance between the start commit given at build time and the provided target commit.
     * Returns the computed distance inside an Optional which can be empty if the given target is not reachable
     * or is too far regarding the given distance.
     * @param target the commit to compute distance for
     * @return a distance as an Optional
     */
    Optional<Integer> distanceTo(ObjectId target);

    class DepthWalkDistanceCalculator implements DistanceCalculator {
        private final ObjectId startId;
        private final Repository repository;
        private final int maxDepth;

        DepthWalkDistanceCalculator(ObjectId start, Repository repository, int maxDepth) {
            this.startId = start;
            this.repository = repository;
            this.maxDepth = maxDepth;
        }

        public Optional<Integer> distanceTo(ObjectId target) {
            DepthWalk.RevWalk walk = null;
            try {
                walk = new DepthWalk.RevWalk(repository, maxDepth);
                RevCommit startCommit = walk.parseCommit(startId);
                walk.markRoot(startCommit);
                walk.setRetainBody(false);

                Iterator<? extends RevCommit> commitIterator = walk.iterator();

                // FIXME remove logging
                System.out.println("startId: " + startId + ", target: " + target);

                while (commitIterator.hasNext()) {
                    RevCommit commit = commitIterator.next();
                    
                    // FIXME remove logging
                    logCommitDetails(repository, commit, commit.getParents());

                    if (commit.getId().getName().equals(target.getName())) {
                        // we found it
                        if (commit instanceof DepthWalk.Commit) {
                            DepthWalk.Commit dwc = (DepthWalk.Commit) commit;
                            return Optional.of(Integer.valueOf(dwc.getDepth()));
                        } else {
                            throw new IllegalStateException(String.format(
                                    "implementation of %s or jgit internal has been incorrectly changed",
                                    DepthWalkDistanceCalculator.class.getSimpleName()
                            ));
                        }
                    }
                }
            } catch (IOException ignore) {
                ignore.printStackTrace();
            } finally {
                if (walk != null) {
                    walk.dispose();
                    walk.close();
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Calculates the distance by trying to find the target commit first on the main branch and then following any other branches.
     */
    class RevWalkDistanceCalculator implements DistanceCalculator {
        private final ObjectId startId;

        private final Repository repository;

        private final int maxDepth;

        RevWalkDistanceCalculator(ObjectId start, Repository repository, int maxDepth) {
            this.startId = start;
            this.repository = repository;
            this.maxDepth = maxDepth;
        }

        public Optional<Integer> distanceTo(ObjectId target) {
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit head = walk.parseCommit(startId);

                // FIXME remove logging
                System.out.println("startId: " + startId + ", target: " + target);

                Deque<RevCommit> parentsStack = new LinkedList<>();
                parentsStack.add(head);

                int commitCount = 0;
                while (!parentsStack.isEmpty()) {

                    // based on https://stackoverflow.com/questions/33038224/how-to-call-git-show-first-parent-in-jgit
                    RevCommit[] parents = head.getParents();

                    // FIXME remove logging
                    logCommitDetails(repository, head, parents);

                    if (head.getId().getName().equals(target.getName())) {
                        // we found it
                        return Optional.of(Integer.valueOf(commitCount));
                    }
                    // get next head
                    if (parents != null && parents.length > 0) {
                        // follow the first parent
                        head = walk.parseCommit(parents[0]);
                        // remember other parents as we may need to follow the other parents as well if
                        // the target is not on the current branch
                        for (int i = 1; i < parents.length; i++) {
                            parentsStack.push(parents[1]);
                        }
                    } else {
                        // traverse next parent and reset count
                        commitCount = 0;
                        RevCommit nextParent = parentsStack.poll();
                        head = walk.parseCommit(nextParent);
                    }
                    if (commitCount >= maxDepth) {
                        return Optional.empty();
                    }
                    commitCount++;
                }
            } catch (IOException ignore) {
                ignore.printStackTrace();
            }
            return Optional.empty();
        }

    }

    // FIXME remove logging
    static void logCommitDetails(Repository repository, RevCommit head, RevCommit[] parents) {
        NameRevCommand revCommand = Git.wrap(repository).nameRev();
        Map<ObjectId, String> map;
        try {
            map = revCommand.add(head.getId()).call();
            String mapAsString = map.keySet().stream().map(key -> key + "=" + map.get(key)).collect(Collectors.joining(", ", "{", "}"));

            System.out.println(head.getId().getName() + " --> " + mapAsString + " --> parents: " + Arrays.toString(parents));
        } catch (MissingObjectException | JGitInternalException | GitAPIException e) {
            e.printStackTrace();
        }
    }

}
