/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.config;

import checkers.igj.quals.Immutable;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import dataflow.quals.Pure;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * Immutable structure to hold the storage config.
 * 
 * Default values should be conservative.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class StorageConfig {

    private final int traceExpirationHours;
    // size of capped database for storing trace details (spans and merged stack traces)
    private final int cappedDatabaseSizeMb;

    private final String version;

    static StorageConfig getDefault() {
        final int traceExpirationHours = 24 * 7;
        final int cappedDatabaseSizeMb = 1000;
        return new StorageConfig(traceExpirationHours, cappedDatabaseSizeMb);
    }

    public static Overlay overlay(StorageConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public StorageConfig(int traceExpirationHours, int cappedDatabaseSizeMb) {
        this.traceExpirationHours = traceExpirationHours;
        this.cappedDatabaseSizeMb = cappedDatabaseSizeMb;
        this.version = VersionHashes.sha1(traceExpirationHours, cappedDatabaseSizeMb);
    }

    public int getTraceExpirationHours() {
        return traceExpirationHours;
    }

    public int getCappedDatabaseSizeMb() {
        return cappedDatabaseSizeMb;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("traceExpirationHours", traceExpirationHours)
                .add("cappedDatabaseSizeMb", cappedDatabaseSizeMb)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private int traceExpirationHours;
        private int cappedDatabaseSizeMb;

        private Overlay(StorageConfig base) {
            traceExpirationHours = base.traceExpirationHours;
            cappedDatabaseSizeMb = base.cappedDatabaseSizeMb;
        }
        public void setTraceExpirationHours(int traceExpirationHours) {
            this.traceExpirationHours = traceExpirationHours;
        }
        public void setCappedDatabaseSizeMb(int cappedDatabaseSizeMb) {
            this.cappedDatabaseSizeMb = cappedDatabaseSizeMb;
        }
        public StorageConfig build() {
            return new StorageConfig(traceExpirationHours, cappedDatabaseSizeMb);
        }
    }
}
