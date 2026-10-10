package com.younglings.bot.internal;

import com.younglings.bot.upload.ImageUploadService;
import com.younglings.bot.upload.UploadedImageRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageAdminApiTest {
    /** A PNG header followed by filler, {@code size} bytes in all. */
    private static byte[] png(int size) {
        byte[] bytes = new byte[size];
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(header, 0, bytes, 0, header.length);
        Arrays.fill(bytes, header.length, size, (byte) 7);
        return bytes;
    }

    @Test
    void aPictureAtTheSizeLimitSurvivesTheJsonRoundTripTheWebsiteSendsItIn() {
        UploadedImageRepository repository = mock(UploadedImageRepository.class);
        when(repository.put(anyLong(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(new UploadedImageRepository.Saved("tok", 9));
        ImageAdminApi api = new ImageAdminApi(new ImageUploadService(repository));
        Guild guild = mock(Guild.class);
        Member actor = mock(Member.class);

        // exactly what the website sends: base64 inside a JSON body, read the way the internal API reads every request
        String json = "{\"data\":\"" + Base64.getEncoder().encodeToString(png(ImageUploadService.MAX_BYTES)) + "\"}";
        DataObject body = DataObject.fromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        DataObject result = api.upload(guild, actor, "welcome", "image", body);

        assertEquals("tok", result.getString("token"));
    }
}
