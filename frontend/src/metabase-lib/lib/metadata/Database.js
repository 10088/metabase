import Question from "../Question";

import Base from "./Base";
import Table from "./Table";
import Schema from "./Schema";

import { memoize, createLookupByProperty } from "metabase-lib/lib/utils";

import { generateSchemaId } from "metabase/schema";

/**
 * Wrapper class for database metadata objects. Contains {@link Schema}s, {@link Table}s, {@link Metric}s, {@link Segment}s.
 *
 * Backed by types/Database data structure which matches the backend API contract
 */
export default class Database extends Base {
  // TODO Atte Keinänen 6/11/17: List all fields here (currently only in types/Database)

  displayName() {
    return this.name;
  }

  // SCHEMAS

  /**
   * @param {import("./metadata").SchemaName} [schemaName]
   */
  schema(schemaName) {
    return this.metadata.schema(generateSchemaId(this.id, schemaName));
  }

  schemaNames() {
    return this.schemas.map(s => s.name).sort((a, b) => a.localeCompare(b));
  }

  // TABLES

  @memoize
  tablesLookup() {
    return createLookupByProperty(this.tables, "id");
  }

  // @deprecated: use tablesLookup
  get tables_lookup() {
    return this.tablesLookup();
  }

  // FEATURES

  /**
   * @typedef {import("./metadata").DatabaseFeature} DatabaseFeature
   * @typedef {"join"} VirtualDatabaseFeature
   * @param {DatabaseFeature | VirtualDatabaseFeature} [feature]
   */
  hasFeature(feature) {
    if (!feature) {
      return true;
    }
    const set = new Set(this.features);
    if (feature === "join") {
      return (
        set.has("left-join") ||
        set.has("right-join") ||
        set.has("inner-join") ||
        set.has("full-join")
      );
    } else {
      return set.has(feature);
    }
  }

  supportsPivots() {
    return this.hasFeature("expressions") && this.hasFeature("left-join");
  }

  // QUESTIONS

  newQuestion() {
    return this.question()
      .setDefaultQuery()
      .setDefaultDisplay();
  }

  question(query = { "source-table": null }) {
    return Question.create({
      metadata: this.metadata,
      dataset_query: {
        database: this.id,
        type: "query",
        query: query,
      },
    });
  }

  nativeQuestion(native = {}) {
    return Question.create({
      metadata: this.metadata,
      dataset_query: {
        database: this.id,
        type: "native",
        native: {
          query: "",
          "template-tags": {},
          ...native,
        },
      },
    });
  }

  nativeQuery(native) {
    return this.nativeQuestion(native).query();
  }

  /** Returns a database containing only the saved questions from the same database, if any */
  savedQuestionsDatabase() {
    return this.metadata.databasesList().find(db => db.is_saved_questions);
  }

  /**
   * @private
   * @param {number} id
   * @param {string} name
   * @param {?string} description
   * @param {Table[]} tables
   * @param {Schema[]} schemas
   * @param {Metadata} metadata
   * @param {boolean} auto_run_queries
   */
  _constructor(
    id,
    name,
    description,
    tables,
    schemas,
    metadata,
    auto_run_queries,
  ) {
    this.id = id;
    this.name = name;
    this.description = description;
    this.tables = tables;
    this.schemas = schemas;
    this.metadata = metadata;
    this.auto_run_queries = auto_run_queries;
  }
}
