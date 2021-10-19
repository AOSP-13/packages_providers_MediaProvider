/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker.data;

import static com.android.providers.media.photopicker.data.ExternalDbFacade.TABLE_FILES;
import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ExternalDbFacadeTest {
    private static final String TAG = "ExternalDbFacadeTest";

    private static final long ID1 = 1;
    private static final long ID2 = 2;
    private static final long ID3 = 3;
    private static final long ID4 = 4;
    private static final long ID5 = 5;
    private static final long DATE_TAKEN_MS1 = 1624886050566L;
    private static final long DATE_TAKEN_MS2 = 1624886050567L;
    private static final long DATE_TAKEN_MS3 = 1624886050568L;
    private static final long DATE_TAKEN_MS4 = 1624886050569L;
    private static final long DATE_TAKEN_MS5 = 1624886050570L;
    private static final long GENERATION_MODIFIED1 = 1;
    private static final long GENERATION_MODIFIED2 = 2;
    private static final long GENERATION_MODIFIED3 = 3;
    private static final long GENERATION_MODIFIED4 = 4;
    private static final long GENERATION_MODIFIED5 = 5;
    private static final long SIZE = 8000;
    private static final String IMAGE_MIME_TYPE = "image/jpeg";
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final long DURATION_MS = 5;
    private static final int IS_FAVORITE = 0;

    private static Context sIsolatedContext;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, TAG, /*asFuseThread*/ false);
    }

    @Test
    public void testDeletedMedia_addAndRemove() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            assertThat(facade.addDeletedMedia(ID1)).isTrue();
            assertThat(facade.addDeletedMedia(ID2)).isTrue();

            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                ArrayList<Long> ids = new ArrayList<>();
                while (cursor.moveToNext()) {
                    ids.add(cursor.getLong(0));
                }

                assertThat(ids).contains(ID1);
                assertThat(ids).contains(ID2);
            }

            // Filter by generation should only return ID2
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertThat(cursor.getLong(0)).isEqualTo(ID2);
            }

            // Adding ids again should succeed but bump generation_modified of ID1 and ID2
            assertThat(facade.addDeletedMedia(ID1)).isTrue();
            assertThat(facade.addDeletedMedia(ID2)).isTrue();

            // Filter by generation again, now returns both ids since their generation_modified was
            // bumped
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 1)) {
                assertThat(cursor.getCount()).isEqualTo(2);
            }

            // Remove ID2 should succeed
            assertThat(facade.removeDeletedMedia(ID2)).isTrue();
            // Remove ID2 again should fail
            assertThat(facade.removeDeletedMedia(ID2)).isFalse();

            // Verify only ID1 left
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertThat(cursor.getLong(0)).isEqualTo(ID1);
            }
        }
    }

    @Test
    public void testDeletedMedia_onInsert() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            assertThat(facade.onFileInserted(FileColumns.MEDIA_TYPE_VIDEO, /* isPending */ false))
                    .isTrue();
            assertThat(facade.onFileInserted(FileColumns.MEDIA_TYPE_IMAGE, /* isPending */ false))
                    .isTrue();
            assertDeletedMediaEmpty(facade);

            assertThat(facade.onFileInserted(FileColumns.MEDIA_TYPE_AUDIO, /* isPending */ false))
                    .isFalse();
            assertThat(facade.onFileInserted(FileColumns.MEDIA_TYPE_NONE, /* isPending */ false))
                    .isFalse();
            assertThat(facade.onFileInserted(FileColumns.MEDIA_TYPE_IMAGE, /* isPending */ true))
                    .isFalse();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_mediaType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            // Non-media -> non-media: no-op
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isFalse();
            assertDeletedMediaEmpty(facade);

            // Media -> non-media: added to deleted_media
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isTrue();
            assertDeletedMedia(facade, ID1);

            // Non-media -> non-media: no-op
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isFalse();
            assertDeletedMedia(facade, ID1);

            // Non-media -> media: remove from deleted_media
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isTrue();
            assertDeletedMediaEmpty(facade);

            // Non-media -> media: no-op
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isFalse();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_trashed() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            // Was trashed but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ true, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isTrue();
            assertDeletedMediaEmpty(facade);

            // Was not trashed but is now trashed
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ true,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isTrue();
            assertDeletedMedia(facade, ID1);

            // Was trashed but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ true, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isTrue();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_pending() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            // Was pending but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ true, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isTrue();
            assertDeletedMediaEmpty(facade);

            // Was not pending but is now pending
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ true,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isTrue();
            assertDeletedMedia(facade, ID1);

            // Was pending but is now neither trashed nor pending
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ true, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ false)).isTrue();
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testOnUpdate_visibleFavorite() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            // Was favorite but is now not favorited
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ true, /* newIsFavorite */ false)).isTrue();

            // Was not favorite but is now favorited
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ false, /* newIsFavorite */ true)).isTrue();
        }
    }

    @Test
    public void testOnUpdate_hiddenFavorite() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            // Was favorite but is now not favorited
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ true, /* newIsTrashed */ true,
                            /* oldIsPending */ false, /* newIsPending */ false,
                            /* oldIsFavorite */ true, /* newIsFavorite */ false)).isFalse();

            // Was not favorite but is now favorited
            assertThat(facade.onFileUpdated(ID1,
                            FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                            /* oldIsTrashed */ false, /* newIsTrashed */ false,
                            /* oldIsPending */ true, /* newIsPending */ true,
                            /* oldIsFavorite */ false, /* newIsFavorite */ true)).isFalse();
        }
    }

    @Test
    public void testDeletedMedia_onDelete() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            assertThat(facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_NONE)).isFalse();
            assertDeletedMediaEmpty(facade);

            assertThat(facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_IMAGE)).isTrue();
            assertDeletedMedia(facade, ID1);

            assertThat(facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_NONE)).isFalse();
            assertDeletedMedia(facade, ID1);
        }
    }

    @Test
    public void testQueryMediaGeneration_match() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            // Intentionally associate <date_taken_ms2 with generation_modifed1>
            // and <date_taken_ms1 with generation_modifed2> below.
            // This allows us verify that the sort order from queryMediaGeneration
            // is based on date_taken and not generation_modified.
            ContentValues cv = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS1);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMediaGeneration(facade)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS2);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMediaGeneration(GENERATION_MODIFIED1,
                            /* albumId */ null, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMediaGeneration_noMatch() throws Exception {
        ContentValues cvPending = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        cvPending.put(MediaColumns.IS_PENDING, 1);

        ContentValues cvTrashed = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
        cvTrashed.put(MediaColumns.IS_TRASHED, 1);

        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvPending));
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvTrashed));

            try (Cursor cursor = queryAllMediaGeneration(facade)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMediaGeneration_withDateModified() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);
            long dateModifiedSeconds1 = DATE_TAKEN_MS1 / 1000;
            long dateModifiedSeconds2 = DATE_TAKEN_MS2 / 1000;
            // Intentionally associate <dateModifiedSeconds2 with generation_modifed1>
            // and <dateModifiedSeconds1 with generation_modifed2> below.
            // This allows us verify that the sort order from queryMediaGeneration
            // is based on date_taken and not generation_modified.
            ContentValues cv = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED1);
            cv.remove(MediaColumns.DATE_TAKEN);
            cv.put(MediaColumns.DATE_MODIFIED, dateModifiedSeconds2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_MODIFIED, dateModifiedSeconds1);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMediaGeneration(facade)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, dateModifiedSeconds2 * 1000);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID2, dateModifiedSeconds1 * 1000);
            }

            try (Cursor cursor = facade.queryMediaGeneration(GENERATION_MODIFIED1,
                            /* albumId */ null, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, dateModifiedSeconds1 * 1000);
            }
        }
    }

    @Test
    public void testQueryMediaGeneration_withMimeType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            // Insert image
            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMediaGeneration(facade)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0,
                            /* albumId */ null, VIDEO_MIME_TYPE)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0,
                            /* albumId */ null, IMAGE_MIME_TYPE)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMediaGeneration_withAlbum() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            initMediaInAllAlbums(helper);

            try (Cursor cursor = queryAllMediaGeneration(facade)) {
                assertThat(cursor.getCount()).isEqualTo(5);
            }

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0,
                            Category.CATEGORY_CAMERA, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0,
                            Category.CATEGORY_SCREENSHOTS, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS2);
            }

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0,
                            Category.CATEGORY_DOWNLOADS, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID3, DATE_TAKEN_MS3);
            }

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0,
                            Category.CATEGORY_VIDEOS, /* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID5, DATE_TAKEN_MS5, /* isFavorite */ 0,
                        VIDEO_MIME_TYPE);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID4, DATE_TAKEN_MS4, /* isFavorite */ 0,
                        VIDEO_MIME_TYPE);
            }
        }
    }

    @Test
    public void testQueryMediaGeneration_withAlbumAndMimeType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            // Insert image
            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            cv.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMediaGeneration(facade)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0,
                            Category.CATEGORY_SCREENSHOTS, IMAGE_MIME_TYPE)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0,
                            Category.CATEGORY_CAMERA, VIDEO_MIME_TYPE)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMediaGeneration(/* generation */ 0,
                            Category.CATEGORY_CAMERA, IMAGE_MIME_TYPE)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMediaId_match() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = facade.queryMediaId(ID1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMediaId_noMatch() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            ContentValues cvPending = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            cvPending.put(MediaColumns.IS_PENDING, 1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvPending));

            ContentValues cvTrashed = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
            cvTrashed.put(MediaColumns.IS_TRASHED, 1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvTrashed));

            try (Cursor cursor = facade.queryMediaId(ID1)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMediaId_withDateModified() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            long dateModifiedSeconds = DATE_TAKEN_MS1 / 1000;
            ContentValues cv = new ContentValues();
            cv.put(MediaColumns.SIZE, SIZE);
            cv.put(MediaColumns.DATE_MODIFIED, dateModifiedSeconds);
            cv.put(FileColumns.MIME_TYPE, IMAGE_MIME_TYPE);
            cv.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_VIDEO);
            cv.put(MediaColumns.DURATION, DURATION_MS);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = facade.queryMediaId(ID1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, dateModifiedSeconds * 1000);
            }
        }
    }

    @Test
    public void testQueryMediaId_withFavorite() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            cv.put(MediaColumns.IS_FAVORITE, 1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = facade.queryMediaId(ID1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1, /* isFavorite */ 1);
            }
        }
    }

    @Test
    public void testGetMediaInfo() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = facade.getMediaInfo(/* generation */ 0)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaInfo(facade, cursor, /* count */ 2, /* generation */ 2);
            }

            try (Cursor cursor = facade.getMediaInfo(GENERATION_MODIFIED1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaInfo(facade, cursor, /* count */ 1, GENERATION_MODIFIED2);
            }

            try (Cursor cursor = facade.getMediaInfo(GENERATION_MODIFIED2)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaInfo(facade, cursor, /* count */ 0, /* generation */ 0);
            }
        }
    }

    @Test
    public void testQueryAlbumsEmpty() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMediaGeneration(facade)) {
                assertThat(cursor.getCount()).isEqualTo(1);
            }

            try (Cursor cursor = facade.queryAlbums(/* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryAlbums() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            initMediaInAllAlbums(helper);

            try (Cursor cursor = queryAllMediaGeneration(facade)) {
                assertThat(cursor.getCount()).isEqualTo(5);
            }

            try (Cursor cursor = facade.queryAlbums(/* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(4);

                // We verify the order of the albums:
                // Camera, Videos, Screenshots and Downloads
                cursor.moveToNext();
                assertAlbumColumns(facade,
                        cursor,
                        Category.getCategoryName(sIsolatedContext, Category.CATEGORY_CAMERA),
                        /* mediaCoverId */ "1",
                        DATE_TAKEN_MS1,
                        /* count */ 1);

                cursor.moveToNext();
                assertAlbumColumns(facade,
                        cursor,
                        Category.getCategoryName(sIsolatedContext, Category.CATEGORY_VIDEOS),
                        /* mediaCoverId */ "5",
                        DATE_TAKEN_MS5,
                        /* count */ 2);

                cursor.moveToNext();
                assertAlbumColumns(facade,
                        cursor,
                        Category.getCategoryName(sIsolatedContext, Category.CATEGORY_SCREENSHOTS),
                        /* mediaCoverId */ "2",
                        DATE_TAKEN_MS2,
                        /* count */ 1);

                cursor.moveToNext();
                assertAlbumColumns(facade,
                        cursor,
                        Category.getCategoryName(sIsolatedContext, Category.CATEGORY_DOWNLOADS),
                        /* mediaCoverId */ "3",
                        DATE_TAKEN_MS3,
                        /* count */ 1);
            }
        }
    }

    @Test
    public void testQueryAlbumsMimeType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper);

            // Insert image in camera album
            ContentValues cv1 = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            cv1.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv1));

            // Insert video in camera ablum
            ContentValues cv2 = getContentValues(DATE_TAKEN_MS5, GENERATION_MODIFIED5);
            cv2.put(FileColumns.MIME_TYPE, VIDEO_MIME_TYPE);
            cv2.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_VIDEO);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv2));

            // Insert video in video ablum
            ContentValues cv3 = getContentValues(DATE_TAKEN_MS4, GENERATION_MODIFIED4);
            cv3.put(FileColumns.MIME_TYPE, VIDEO_MIME_TYPE);
            cv3.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_VIDEO);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv3));

            try (Cursor cursor = queryAllMediaGeneration(facade)) {
                assertThat(cursor.getCount()).isEqualTo(3);
            }

            try (Cursor cursor = facade.queryAlbums(IMAGE_MIME_TYPE)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                // We verify the order of the albums only the image in camera is shown
                cursor.moveToNext();
                assertAlbumColumns(facade,
                        cursor,
                        Category.getCategoryName(sIsolatedContext, Category.CATEGORY_CAMERA),
                        /* mediaCoverId */ "1",
                        DATE_TAKEN_MS1,
                        /* count */ 1);
            }
        }
    }

    private static void initMediaInAllAlbums(DatabaseHelper helper) {
        // Insert in camera album
        ContentValues cv1 = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        cv1.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv1));

        // Insert in screenshots ablum
        ContentValues cv2 = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
        cv2.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_SCREENSHOTS);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv2));

        // Insert in download ablum
        ContentValues cv3 = getContentValues(DATE_TAKEN_MS3, GENERATION_MODIFIED3);
        cv3.put(MediaColumns.IS_DOWNLOAD, 1);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv3));

        // Insert in video ablum
        ContentValues cv4 = getContentValues(DATE_TAKEN_MS4, GENERATION_MODIFIED4);
        cv4.put(FileColumns.MIME_TYPE, VIDEO_MIME_TYPE);
        cv4.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_VIDEO);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv4));

        // Insert again in video ablum
        ContentValues cv5 = getContentValues(DATE_TAKEN_MS5, GENERATION_MODIFIED5);
        cv5.put(FileColumns.MIME_TYPE, VIDEO_MIME_TYPE);
        cv5.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_VIDEO);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv5));
    }

    private static void assertDeletedMediaEmpty(ExternalDbFacade facade) {
        try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    private static void assertDeletedMedia(ExternalDbFacade facade, long id) {
        try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
            assertThat(cursor.getCount()).isEqualTo(1);

            cursor.moveToFirst();
            assertThat(cursor.getLong(0)).isEqualTo(id);
            assertThat(cursor.getColumnName(0)).isEqualTo(
                    CloudMediaProviderContract.MediaColumns.ID);
        }
    }

    private static void assertMediaColumns(ExternalDbFacade facade, Cursor cursor, long id,
            long dateTakenMs) {
        assertMediaColumns(facade, cursor, id, dateTakenMs, IS_FAVORITE);
    }

    private static void assertMediaColumns(ExternalDbFacade facade, Cursor cursor, long id,
            long dateTakenMs, int isFavorite) {
        assertMediaColumns(facade, cursor, id, dateTakenMs, isFavorite, IMAGE_MIME_TYPE);
    }

    private static void assertMediaColumns(ExternalDbFacade facade, Cursor cursor, long id,
            long dateTakenMs, int isFavorite, String mimeType) {
        int idIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaColumns.ID);
        int dateTakenIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MS);
        int sizeIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaColumns.SIZE_BYTES);
        int mimeTypeIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.MIME_TYPE);
        int durationIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.DURATION_MS);
        int isFavoriteIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.IS_FAVORITE);

        assertThat(cursor.getLong(idIndex)).isEqualTo(id);
        assertThat(cursor.getLong(dateTakenIndex)).isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(sizeIndex)).isEqualTo(SIZE);
        assertThat(cursor.getString(mimeTypeIndex)).isEqualTo(mimeType);
        assertThat(cursor.getLong(durationIndex)).isEqualTo(DURATION_MS);
        assertThat(cursor.getInt(isFavoriteIndex)).isEqualTo(isFavorite);
    }

    private static void assertAlbumColumns(ExternalDbFacade facade, Cursor cursor,
            String displayName, String mediaCoverId, long dateTakenMs, long count) {
        int displayNameIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME);
        int idIndex = cursor.getColumnIndex(CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID);
        int dateTakenIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MS);
        int countIndex = cursor.getColumnIndex(CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT);

        assertThat(cursor.getString(displayNameIndex)).isEqualTo(displayName);
        assertThat(cursor.getString(idIndex)).isEqualTo(mediaCoverId);
        assertThat(cursor.getLong(dateTakenIndex)).isEqualTo(dateTakenMs);
        assertThat(cursor.getLong(countIndex)).isEqualTo(count);
    }

    private static void assertMediaInfo(ExternalDbFacade facade, Cursor cursor,
            long count, long generation) {
        int countIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaInfo.MEDIA_COUNT);
        int generationIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaInfo.MEDIA_GENERATION);

        assertThat(cursor.getLong(countIndex)).isEqualTo(count);
        assertThat(cursor.getLong(generationIndex)).isEqualTo(generation);
    }

    private static Cursor queryAllMediaGeneration(ExternalDbFacade facade) {
        return facade.queryMediaGeneration(/* generation */ 0, /* albumId */ null,
                /* mimeType */ null);
    }

    private static ContentValues getContentValues(long dateTakenMs, long generation) {
        ContentValues cv = new ContentValues();
        cv.put(MediaColumns.SIZE, SIZE);
        cv.put(MediaColumns.DATE_TAKEN, dateTakenMs);
        cv.put(FileColumns.MIME_TYPE, IMAGE_MIME_TYPE);
        cv.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_IMAGE);
        cv.put(MediaColumns.DURATION, DURATION_MS);
        cv.put(MediaColumns.GENERATION_MODIFIED, generation);

        return cv;
    }

    private static class TestDatabaseHelper extends DatabaseHelper {
        public TestDatabaseHelper(Context context) {
            super(context, TEST_CLEAN_DB, 1,
                    false, false, null, null, null, null, null);
        }
    }
}