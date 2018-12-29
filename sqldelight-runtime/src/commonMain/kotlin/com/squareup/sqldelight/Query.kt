/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.sqldelight

import com.squareup.sqldelight.db.SqlDatabase
import com.squareup.sqldelight.db.SqlPreparedStatement
import com.squareup.sqldelight.db.SqlPreparedStatement.Type.SELECT
import com.squareup.sqldelight.db.SqlCursor
import com.squareup.sqldelight.db.use
import com.squareup.sqldelight.internal.Atomic
import com.squareup.sqldelight.internal.QueryLock
import com.squareup.sqldelight.internal.sharedSet
import com.squareup.sqldelight.internal.withLock

fun <RowType : Any> Query(
  identifier: Int,
  queries: MutableList<Query<*>>,
  database: SqlDatabase,
  query: String,
  mapper: (SqlCursor) -> RowType
): Query<RowType> {
  return SimpleQuery(identifier, queries, database, query, mapper)
}

private class SimpleQuery<out RowType : Any>(
  private val identifier: Int,
  queries: MutableList<Query<*>>,
  private val database: SqlDatabase,
  private val query: String,
  mapper: (SqlCursor) -> RowType
) : Query<RowType>(queries, mapper) {
  override fun createStatement(): SqlPreparedStatement {
    return database.getConnection().prepareStatement(identifier, query, SELECT, 0)
  }
}

/**
 * A listenable, typed query generated by SQLDelight.
 *
 * @param RowType the type that this query can map it's result set to.
 */
abstract class Query<out RowType : Any>(
  private val queries: MutableList<Query<*>>,
  private val mapper: (SqlCursor) -> RowType
) {
  private val listenerLock = QueryLock()
  private val listeners = sharedSet<Listener>()

  protected abstract fun createStatement(): SqlPreparedStatement

  /**
   * Notify listeners that their current result set is staled.
   *
   * Called internally by SQLDelight when it detects a possible staling of the result set. Emits
   * some false positives but never misses a true positive.
   */
  fun notifyDataChanged() {
    listenerLock.withLock {
      listeners.forEach(Listener::queryResultsChanged)
    }
  }

  /**
   * Register a listener to be notified of future changes in the result set.
   */
  fun addListener(listener: Listener) {
    listenerLock.withLock {
      if (listeners.isEmpty()) queries.add(this)
      listeners.add(listener)
    }
  }

  fun removeListener(listener: Listener) {
    listenerLock.withLock {
      listeners.remove(listener)
      if (listeners.isEmpty()) queries.remove(this)
    }
  }

  /**
   * Execute [statement] as a query.
   */
  fun execute() = createStatement().executeQuery()

  /**
   * Execute [statement] and return the result set as a list of [RowType].
   */
  fun executeAsList(): List<RowType> {
    val result = mutableListOf<RowType>()
    execute().use {
      while (it.next()) result.add(mapper(it))
    }
    return result
  }

  /**
   * Execute [statement] and return the only row of the result set as a non null [RowType].
   *
   * @throws NullPointerException if when executed this query has no rows in its result set.
   * @throws IllegalStateException if when executed this query has multiple rows in its result set.
   */
  fun executeAsOne(): RowType {
    return executeAsOneOrNull()
        ?: throw NullPointerException("ResultSet returned null for ${createStatement()}")
  }

  /**
   * Execute [statement] and return the first row of the result set as a non null [RowType] or null
   * if the result set has no rows.
   *
   * @throws IllegalStateException if when executed this query has multiple rows in its result set.
   */
  fun executeAsOneOrNull(): RowType? {
    execute().use {
      if (!it.next()) return null
      val item = mapper(it)
      if (it.next()) {
        throw IllegalStateException("ResultSet returned more than 1 row for ${createStatement()}")
      }
      return item
    }
  }

  interface Listener {
    fun queryResultsChanged()
  }
}
