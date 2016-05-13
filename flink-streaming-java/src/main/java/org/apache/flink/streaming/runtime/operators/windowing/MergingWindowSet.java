/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.streaming.runtime.operators.windowing;

import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.windowing.assigners.MergingWindowAssigner;
import org.apache.flink.streaming.api.windowing.windows.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for keeping track of merging {@link Window Windows} when using a
 * {@link MergingWindowAssigner} in a {@link WindowOperator}.
 *
 * <p>When merging windows, we keep one of the original windows as the state window, i.e. the
 * window that is used as namespace to store the window elements. Elements from the state
 * windows of merged windows must be merged into this one state window. We keep
 * a mapping from in-flight window to state window that can be queried using
 * {@link #getStateWindow(Window)}.
 *
 * <p>A new window can be added to the set of in-flight windows using
 * {@link #addWindow(Window, MergeFunction)}, this might merge other windows and the caller
 * must react accordingly in {@link MergeFunction#merge(Object, Collection, Object, Collection)
 * and adjust the outside view of windows and state.
 *
 * <p>Windows can be removed from the set of windows using {@link #retireWindow(Window)}.
 *
 * @param <W> The type of {@code Window} that this set is keeping track of.
 */
public class MergingWindowSet<W extends Window> {

	private static final Logger LOG = LoggerFactory.getLogger(MergingWindowSet.class);

	/**
	 * Mapping from window to the window that keeps the window state. When
	 * we are incrementally merging windows starting from some window we keep that starting
	 * window as the state window to prevent costly state juggling.
	 */
	private final Map<W, W> windows;

	/**
	 * Our window assigner.
	 */
	private final MergingWindowAssigner<?, W> windowAssigner;

	/**
	 * Creates a new {@link MergingWindowSet} that uses the given {@link MergingWindowAssigner}.
	 */
	public MergingWindowSet(MergingWindowAssigner<?, W> windowAssigner) {
		this.windowAssigner = windowAssigner;

		windows = new HashMap<>();
	}

	/**
	 * Restores a {@link MergingWindowSet} from the given state.
	 */
	public MergingWindowSet(MergingWindowAssigner<?, W> windowAssigner, ListState<Tuple2<W, W>> state) throws Exception {
		this.windowAssigner = windowAssigner;
		windows = new HashMap<>();

		for (Tuple2<W, W> window: state.get()) {
			windows.put(window.f0, window.f1);
		}
	}

	public void persist(ListState<Tuple2<W, W>> state) throws Exception {
		for (Map.Entry<W, W> window: windows.entrySet()) {
			state.add(new Tuple2<>(window.getKey(), window.getValue()));
		}
	}

	/**
	 * Returns the state window for the given in-flight {@code Window}. The state window is the
	 * {@code Window} in which we keep the actual state of a given in-flight window. Windows
	 * might expand but we keep to original state window for keeping the elements of the window
	 * to avoid costly state juggling.
	 *
	 * @param window The window for which to get the state window.
	 */
	public W getStateWindow(W window) {
		W result = windows.get(window);
		if (result == null) {
			throw new IllegalStateException("Window " + window + " is not in in-flight window set.");
		}

		return result;
	}

	/**
	 * Removes the given window from the set of in-flight windows.
	 *
	 * @param window The {@code Window} to remove.
	 */
	public void retireWindow(W window) {
		W removed = this.windows.remove(window);
		if (removed == null) {
			throw new IllegalStateException("Window " + window + " is not in in-flight window set.");
		}
	}

	/**
	 * Adds a new {@code Window} to the set of in-flight windows. It might happen that this
	 * triggers merging of previously in-flight windows. In that case, the provided
	 * {@link MergeFunction} is called.
	 *
	 * <p>This returns the window that is the representative of the added window after adding.
	 * This can either be the new window itself, if no merge occured, or the newly merged
	 * window. Adding an element to a window or calling trigger functions should only
	 * happen on the returned representative. This way, we never have to deal with a new window
	 * that is immediately swallowed up by another window.
	 *
	 * <p>If the new window is merged the {@code MergeFunction} callback arguments also don't
	 * contain the new window as part of the list of merged windows.
	 *
	 * @param newWindow The new {@code Window} to add.
	 * @param mergeFunction The callback to be invoked in case a merge occurs.
	 *
	 * @return The {@code Window} that new new {@code Window} ended up in. This can also be the
	 *          the new {@code Window} itself in case no merge occurred.
	 * @throws Exception
	 */
	public W addWindow(W newWindow, MergeFunction<W> mergeFunction) throws Exception {

		List<W> windows = new ArrayList<>();

		windows.addAll(this.windows.keySet());
		windows.add(newWindow);

		final Map<W, Collection<W>> mergeResults = new HashMap<>();
		windowAssigner.mergeWindows(windows,
				new MergingWindowAssigner.MergeCallback<W>() {
					@Override
					public void merge(Collection<W> toBeMerged, W mergeResult) {
						if (LOG.isDebugEnabled()) {
							LOG.debug("Merging {} into {}", toBeMerged, mergeResult);
						}
						mergeResults.put(mergeResult, toBeMerged);
					}
				});

		W resultWindow = newWindow;

		// perform the merge
		for (Map.Entry<W, Collection<W>> c: mergeResults.entrySet()) {
			W mergeResult = c.getKey();
			Collection<W> mergedWindows = c.getValue();

			// if our new window is in the merged windows make the merge result the
			// result window
			if (mergedWindows.remove(newWindow)) {
				resultWindow = mergeResult;
			}

			// pick any of the merged windows and choose that window's state window
			// as the state window for the merge result
			W mergedStateWindow = this.windows.get(mergedWindows.iterator().next());

			// figure out the state windows that we are merging
			List<W> mergedStateWindows = new ArrayList<>();
			for (W mergedWindow: mergedWindows) {
				W res = this.windows.remove(mergedWindow);
				if (res != null) {
					mergedStateWindows.add(res);
				}
			}

			this.windows.put(mergeResult, mergedStateWindow);

			// don't put the target state window into the merged windows
			mergedStateWindows.remove(mergedStateWindow);

			// don't merge the new window itself, it never had any state associated with it
			// i.e. if we are only merging one pre-existing window into itself
			// without extending the pre-exising window
			if (!(mergedWindows.contains(mergeResult) && mergedWindows.size() == 1)) {
				mergeFunction.merge(mergeResult,
						mergedWindows,
						this.windows.get(mergeResult),
						mergedStateWindows);
			}
		}

		// the new window created a new, self-contained window without merging
		if (resultWindow.equals(newWindow)) {
			this.windows.put(resultWindow, resultWindow);
		}

		return resultWindow;
	}

	/**
	 * Callback for {@link #addWindow(Window, MergeFunction)}.
	 * @param <W>
	 */
	public interface MergeFunction<W> {

		/**
		 * This gets called when a merge occurs.
		 *
		 * @param mergeResult The newly resulting merged {@code Window}.
		 * @param mergedWindows The merged {@code Window Windows}.
		 * @param stateWindowResult The state window of the merge result.
		 * @param mergedStateWindows The merged state windows.
		 * @throws Exception
		 */
		void merge(W mergeResult, Collection<W> mergedWindows, W stateWindowResult, Collection<W> mergedStateWindows) throws Exception;
	}
}
