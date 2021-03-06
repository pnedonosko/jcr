/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.exoplatform.services.jcr.impl.core.query.lucene;

import java.io.IOException;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.exoplatform.services.jcr.datamodel.InternalQName;


/**
 * <code>MultiColumnQueryAdapter</code> adapts a lucene query to act like a
 * {@link MultiColumnQuery}.
 */
public class MultiColumnQueryAdapter implements MultiColumnQuery {

    /**
     * The underlying lucene query.
     */
    private final Query query;

    /**
     * The selector name for the query hits.
     */
    private final InternalQName selectorName;

    /**
     * Creates a new adapter for the given <code>query</code>.
     *
     * @param query        a lucene query.
     * @param selectorName the selector name for the query hits.
     */
    private MultiColumnQueryAdapter(Query query, InternalQName selectorName) {
        this.query = query;
        this.selectorName = selectorName;
    }

    /**
     * Adapts the given <code>query</code>.
     *
     * @param query        the lucene query to adapt.
     * @param selectorName the selector name for the query hits.
     * @return a {@link MultiColumnQuery} that wraps the given lucene query.
     */
    public static MultiColumnQuery adapt(Query query, InternalQName selectorName) {
        return new MultiColumnQueryAdapter(query, selectorName);
    }

    /**
     * {@inheritDoc}
     */
    public MultiColumnQueryHits execute(JcrIndexSearcher searcher,
                                        Sort sort,
                                        long resultFetchHint)
            throws IOException {
        return searcher.execute(query, sort, resultFetchHint, selectorName);
    }
}
