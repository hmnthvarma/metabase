import { createSelector } from "@reduxjs/toolkit";
import { getIn } from "icepick";

import Dashboards from "metabase/entities/dashboards";
import { resourceListToMap } from "metabase/lib/redux";
import {
  getShallowDatabases as getDatabases,
  getShallowFields as getFields,
  getShallowSegments as getSegments,
  getShallowTables as getTables,
} from "metabase/selectors/metadata";

import { databaseToForeignKeys, idsToObjectMap } from "./utils";

// import { getDatabases, getTables, getFields, getSegments } from "metabase/selectors/metadata";

export {
  getShallowDatabases as getDatabases,
  getShallowTables as getTables,
  getShallowFields as getFields,
  getShallowSegments as getSegments,
} from "metabase/selectors/metadata";

export const getUser = (state, props) => state.currentUser;

export const getSegmentId = (state, props) =>
  Number.parseInt(props.params.segmentId);
export const getSegment = createSelector(
  [getSegmentId, getSegments],
  (segmentId, segments) => segments[segmentId] || { id: segmentId },
);

export const getDatabaseId = (state, props) =>
  Number.parseInt(props.params.databaseId);

export const getDatabase = createSelector(
  [getDatabaseId, getDatabases],
  (databaseId, databases) => databases[databaseId] || { id: databaseId },
);

export const getTableId = (state, props) =>
  Number.parseInt(props.params.tableId);
// export const getTableId = (state, props) => Number.parseInt(props.params.tableId);
export const getTablesByDatabase = createSelector(
  [getTables, getDatabase],
  (tables, database) =>
    tables && database && database.tables
      ? idsToObjectMap(database.tables, tables)
      : {},
);
export const getTableBySegment = createSelector(
  [getSegment, getTables],
  (segment, tables) =>
    segment && segment.table_id ? tables[segment.table_id] : {},
);
export const getTable = createSelector(
  [getTableId, getTables, getSegmentId, getTableBySegment],
  (tableId, tables, segmentId, tableBySegment) =>
    tableId
      ? tables[tableId] || { id: tableId }
      : segmentId
        ? tableBySegment
        : {},
);

export const getFieldId = (state, props) =>
  Number.parseInt(props.params.fieldId);
export const getFieldsByTable = createSelector(
  [getTable, getFields],
  (table, fields) =>
    table && table.fields ? idsToObjectMap(table.fields, fields) : {},
);
export const getFieldsBySegment = createSelector(
  [getTableBySegment, getFields],
  (table, fields) =>
    table && table.fields ? idsToObjectMap(table.fields, fields) : {},
);
export const getField = createSelector(
  [getFieldId, getFields],
  (fieldId, fields) => fields[fieldId] || { id: fieldId },
);
export const getFieldBySegment = createSelector(
  [getFieldId, getFieldsBySegment],
  (fieldId, fields) => fields[fieldId] || { id: fieldId },
);

const getQuestions = (state, props) =>
  getIn(state, ["entities", "questions"]) || {};

const getRevisions = (state, props) => state.revisions;

export const getSegmentRevisions = createSelector(
  [getSegmentId, getRevisions],
  (segmentId, revisions) => getIn(revisions, ["segment", segmentId]) || {},
);

export const getTableQuestions = createSelector(
  [getTable, getQuestions],
  (table, questions) =>
    Object.values(questions).filter(
      (question) => question.table_id === table.id,
    ),
);

const getDatabaseBySegment = createSelector(
  [getSegment, getTables, getDatabases],
  (segment, tables, databases) =>
    (segment &&
      segment.table_id &&
      tables[segment.table_id] &&
      databases[tables[segment.table_id].db_id]) ||
    {},
);

const getForeignKeysBySegment = createSelector(
  [getDatabaseBySegment],
  databaseToForeignKeys,
);

const getForeignKeysByDatabase = createSelector(
  [getDatabase],
  databaseToForeignKeys,
);

export const getForeignKeys = createSelector(
  [getSegmentId, getForeignKeysBySegment, getForeignKeysByDatabase],
  (segmentId, foreignKeysBySegment, foreignKeysByDatabase) =>
    segmentId ? foreignKeysBySegment : foreignKeysByDatabase,
);

export const getLoading = (state, props) => state.reference.isLoading;

export const getError = (state, props) => state.reference.error;

export const getHasSingleSchema = createSelector(
  [getTablesByDatabase],
  (tables) =>
    tables && Object.keys(tables).length > 0
      ? Object.values(tables).every(
          (table, index, tables) => table.schema_name === tables[0].schema,
        )
      : true,
);

export const getIsEditing = (state, props) => state.reference.isEditing;

export const getIsFormulaExpanded = (state, props) =>
  state.reference.isFormulaExpanded;

export const getDashboards = (state, props) => {
  const list = Dashboards.selectors.getList(state);
  return list && resourceListToMap(list);
};

export const getIsDashboardModalOpen = (state, props) =>
  state.reference.isDashboardModalOpen;
