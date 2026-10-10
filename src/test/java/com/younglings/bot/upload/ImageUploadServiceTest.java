package com.younglings.bot.upload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageUploadServiceTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 0, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    private UploadedImageRepository repository;
    private ImageUploadService service;

    @BeforeEach
    void setUp() {
        repository = mock(UploadedImageRepository.class);
        when(repository.put(anyLong(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(new UploadedImageRepository.Saved("tok", 5));
        service = new ImageUploadService(repository);
    }

    @Test
    void theRealTypeComesFromTheBytes() {
        assertEquals("image/png", ImageUploadService.sniff(PNG).orElseThrow());
        assertEquals("image/jpeg", ImageUploadService.sniff(JPEG).orElseThrow());
        assertEquals("image/gif", ImageUploadService.sniff(GIF).orElseThrow());
        assertEquals("image/webp", ImageUploadService.sniff(WEBP).orElseThrow());
    }

    @Test
    void anythingElseIsRefusedIncludingSvgAndHtmlPassingAsAPicture() {
        assertTrue(ImageUploadService.sniff("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()).isEmpty());
        assertTrue(ImageUploadService.sniff("<html><script>alert(1)</script></html>".getBytes()).isEmpty());
        assertThrows(ImageUploadService.InvalidImageException.class, () -> service.save(1, "welcome", "image", "not a picture".getBytes()));
        verify(repository, never()).put(anyLong(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aPictureIsStoredUnderItsRealTypeForThatPlace() {
        var saved = service.save(7, "welcome", "thumbnail", PNG);

        assertEquals("tok", saved.token());
        verify(repository).put(eq(7L), eq("welcome"), eq("thumbnail"), anyString(), eq("image/png"), eq(PNG));
    }

    @Test
    void aTooBigOrEmptyFileIsRefused() {
        byte[] huge = Arrays.copyOf(PNG, ImageUploadService.MAX_BYTES + 1);

        assertThrows(ImageUploadService.InvalidImageException.class, () -> service.save(1, "welcome", "image", huge));
        assertThrows(ImageUploadService.InvalidImageException.class, () -> service.save(1, "welcome", "image", new byte[0]));
    }

    @Test
    void onlyKnownPlacesTakeAPicture() {
        assertTrue(ImageUploadService.isKnownSlot("welcome", "footer_icon"));
        assertFalse(ImageUploadService.isKnownSlot("welcome", "../etc"));
        assertFalse(ImageUploadService.isKnownSlot("tickets", "image"));
        assertThrows(ImageUploadService.InvalidImageException.class, () -> service.save(1, "tickets", "image", PNG));
        assertFalse(service.remove(1, "tickets", "image"));
        verify(repository, never()).delete(anyLong(), anyString(), anyString());
    }

    @Test
    void aLookupOnlyAnswersToARealLookingToken() {
        assertTrue(service.find("not-a-token").isEmpty());
        assertTrue(service.find("../../x").isEmpty());
        verify(repository, never()).findByToken(anyString());

        service.find("0123456789abcdef0123456789abcdef");
        verify(repository).findByToken("0123456789abcdef0123456789abcdef");
    }
}
