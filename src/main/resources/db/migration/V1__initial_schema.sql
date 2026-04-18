-- Create trips table
CREATE TABLE trips (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    destination VARCHAR(255),
    start_date DATE,
    end_date DATE,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create photos table
CREATE TABLE photos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    original_filename VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    metadata JSONB NOT NULL,
    signals JSONB,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    analyzed_at TIMESTAMP
);

CREATE INDEX idx_photos_trip_id ON photos(trip_id);
CREATE INDEX idx_photos_signals ON photos USING GIN (signals);

-- Create books table
CREATE TABLE books (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    version INTEGER NOT NULL DEFAULT 1,
    title VARCHAR(255) NOT NULL,
    subtitle VARCHAR(255),
    cover_photo_id UUID REFERENCES photos(id) ON DELETE SET NULL,
    status VARCHAR(50) NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pdf_url VARCHAR(1024)
);

CREATE INDEX idx_books_trip_id ON books(trip_id);
CREATE INDEX idx_books_trip_version ON books(trip_id, version);

-- Create pages table
CREATE TABLE pages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id UUID NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    page_number INTEGER NOT NULL,
    layout VARCHAR(50) NOT NULL,
    slots JSONB NOT NULL
);

CREATE INDEX idx_pages_book_id ON pages(book_id);
CREATE INDEX idx_pages_book_page_number ON pages(book_id, page_number);
