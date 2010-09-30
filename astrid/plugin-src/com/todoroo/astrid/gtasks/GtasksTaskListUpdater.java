package com.todoroo.astrid.gtasks;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import android.text.TextUtils;

import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.astrid.api.Filter;
import com.todoroo.astrid.core.PluginServices;
import com.todoroo.astrid.data.Metadata;
import com.todoroo.astrid.data.StoreObject;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.gtasks.sync.GtasksTaskContainer;

public class GtasksTaskListUpdater {

    @Autowired private GtasksListService gtasksListService;
    @Autowired private GtasksMetadataService gtasksMetadataService;

    final HashMap<Long, Long> parents = new HashMap<Long, Long>();
    final HashMap<Long, Long> siblings = new HashMap<Long, Long>();

    final HashMap<Long, String> localToRemoteIdMap =
        new HashMap<Long, String>();

    public GtasksTaskListUpdater() {
        DependencyInjectionService.getInstance().inject(this);
    }

    // --- used during normal ui operations

    public void debugPrint(String listId) {
        StoreObject list = gtasksListService.getList(listId);
        if(list == GtasksListService.LIST_NOT_FOUND_OBJECT)
            return;

        iterateThroughList(list, new ListIterator() {
            public void processTask(long taskId, Metadata metadata) {
                System.err.format("%d: indent:%d, parent:%d\n", taskId, //$NON-NLS-1$
                        metadata.getValue(GtasksMetadata.INDENT),
                        metadata.getValue(GtasksMetadata.PARENT_TASK));
            }
        });
    }

    /**
     * Indent a task and all its children
     */
    public void indent(String listId, final long targetTaskId, final int delta) {
        StoreObject list = gtasksListService.getList(listId);
        if(list == GtasksListService.LIST_NOT_FOUND_OBJECT)
            return;

        updateParentSiblingMapsFor(list);

        final AtomicInteger targetTaskIndent = new AtomicInteger(-1);
        final AtomicInteger previousIndent = new AtomicInteger(-1);
        final AtomicLong previousTask = new AtomicLong(-1);

        iterateThroughList(list, new ListIterator() {
            @Override
            public void processTask(long taskId, Metadata metadata) {
                int indent = metadata.getValue(GtasksMetadata.INDENT);

                if(targetTaskId == taskId) {
                    // if indenting is warranted, indent me and my children
                    if(indent + delta <= previousIndent.get() + 1 && indent + delta >= 0) {
                        targetTaskIndent.set(indent);
                        metadata.setValue(GtasksMetadata.INDENT, indent + delta);
                        if(delta > 0)
                            metadata.setValue(GtasksMetadata.PARENT_TASK, previousTask.get());
                        else if(parents.containsKey(taskId))
                            metadata.setValue(GtasksMetadata.PARENT_TASK,
                                    parents.get(parents.get(taskId)));
                        else
                            metadata.setValue(GtasksMetadata.PARENT_TASK, Task.NO_ID);
                        PluginServices.getMetadataService().save(metadata);
                    }
                } else if(targetTaskIndent.get() > -1) {
                    // found first task that is not beneath target
                    if(indent <= targetTaskIndent.get())
                        targetTaskIndent.set(-1);
                    else {
                        metadata.setValue(GtasksMetadata.INDENT, indent + delta);
                        PluginServices.getMetadataService().save(metadata);
                    }
                } else {
                    previousIndent.set(indent);
                    previousTask.set(taskId);
                }
            }
        });
    }

    // --- used during synchronization


    /**
     * Update order, parent, and indentation fields for all tasks in all lists
     */
    public void updateAllMetadata() {
        for(StoreObject list : gtasksListService.getLists()) {
            correctMetadataForList(list.getValue(GtasksList.REMOTE_ID));
        }
    }

    /**
     * Update order, parent, and indentation fields for all tasks in the given list
     * @param listId
     */
    public void correctMetadataForList(String listId) {
        StoreObject list = gtasksListService.getList(listId);
        if(list == GtasksListService.LIST_NOT_FOUND_OBJECT)
            return;

        updateParentSiblingMapsFor(list);

        final AtomicInteger order = new AtomicInteger(0);
        final AtomicInteger previousIndent = new AtomicInteger(-1);

        iterateThroughList(list, new ListIterator() {
            @Override
            public void processTask(long taskId, Metadata metadata) {
                metadata.setValue(GtasksMetadata.ORDER, order.getAndAdd(1));
                int indent = metadata.getValue(GtasksMetadata.INDENT);
                if(indent > previousIndent.get() + 1)
                    indent = previousIndent.get() + 1;
                metadata.setValue(GtasksMetadata.INDENT, indent);

                long parent = parents.get(taskId);
                metadata.setValue(GtasksMetadata.PARENT_TASK, parent);

                PluginServices.getMetadataService().save(metadata);
                previousIndent.set(indent);
            }
        });
    }

    /**
     * Create a local tree of tasks to expedite sibling and parent lookups
     */
    public void createParentSiblingMaps() {
        for(StoreObject list : gtasksListService.getLists()) {
            updateParentSiblingMapsFor(list);
        }
    }

    private void updateParentSiblingMapsFor(StoreObject list) {
        final AtomicLong previousTask = new AtomicLong(-1L);
        final AtomicInteger previousIndent = new AtomicInteger(-1);

        iterateThroughList(list, new ListIterator() {
            @Override
            public void processTask(long taskId, Metadata metadata) {
                int indent = metadata.getValue(GtasksMetadata.INDENT);

                long parent, sibling;
                if(indent > previousIndent.get()) {
                    parent = previousTask.get();
                    sibling = -1L;
                } else if(indent == previousIndent.get()) {
                    sibling = previousTask.get();
                    parent = parents.get(sibling);
                } else {
                    // move up once for each indent
                    sibling = previousTask.get();
                    for(int i = indent; i < previousIndent.get(); i++)
                        sibling = parents.get(sibling);
                    parent = parents.get(sibling);
                }
                parents.put(taskId, parent);
                siblings.put(taskId, sibling);

                previousTask.set(taskId);
                previousIndent.set(indent);
                if(!TextUtils.isEmpty(metadata.getValue(GtasksMetadata.ID)))
                    localToRemoteIdMap.put(taskId, metadata.getValue(GtasksMetadata.ID));
            }
        });
    }

    /**
     * Must be called after creating parent and sibling maps. Updates a
     * task container's parent and sibling fields.
     *
     * @param container
     */
    public void updateParentAndSibling(GtasksTaskContainer container) {
        long taskId = container.task.getId();
        if(parents.containsKey(taskId)) {
            long parentId = parents.get(taskId);
            if(localToRemoteIdMap.containsKey(parentId))
                container.parentId = localToRemoteIdMap.get(parentId);
        }
        if(siblings.containsKey(taskId)) {
            long siblingId = siblings.get(taskId);
            if(localToRemoteIdMap.containsKey(siblingId))
                container.priorSiblingId = localToRemoteIdMap.get(siblingId);
        }
    }

    public void addRemoteTaskMapping(long id, String remoteId) {
        localToRemoteIdMap.put(id, remoteId);
    }

    // --- private helpers

    private interface ListIterator {
        public void processTask(long taskId, Metadata metadata);
    }

    private void iterateThroughList(StoreObject list, ListIterator iterator) {
        Filter filter = GtasksFilterExposer.filterFromList(list);
        TodorooCursor<Task> cursor = PluginServices.getTaskService().fetchFiltered(filter.sqlQuery, null, Task.ID);
        try {
            for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                long taskId = cursor.getLong(0);
                Metadata metadata = gtasksMetadataService.getTaskMetadata(taskId);
                if(metadata == null)
                    continue;
                iterator.processTask(taskId, metadata);
            }

        } finally {
            cursor.close();
        }
    }

}

