WITH ranked_primary_thumbnails AS (
    SELECT
        thumbnail_id,
        ROW_NUMBER() OVER (
            PARTITION BY object_id
            ORDER BY updated_at DESC, created_at DESC, thumbnail_id DESC
        ) AS row_number
    FROM media_thumbnails
    WHERE is_primary = 1
)
UPDATE media_thumbnails
SET is_primary = CASE
    WHEN thumbnail_id IN (
        SELECT thumbnail_id
        FROM ranked_primary_thumbnails
        WHERE row_number = 1
    ) THEN 1
    ELSE 0
END
WHERE thumbnail_id IN (SELECT thumbnail_id FROM ranked_primary_thumbnails);

DROP INDEX IF EXISTS idx_media_thumbnails_object_primary;

CREATE UNIQUE INDEX IF NOT EXISTS idx_media_thumbnails_primary_object
    ON media_thumbnails(object_id)
    WHERE is_primary = 1;
