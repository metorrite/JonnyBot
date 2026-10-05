package com.younglings.bot.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DevEmbedTest {
    @Test
    void theFormHasTheTextBoxAndTheTidyCheckbox() {
        var modal = DevEmbedListener.buildModal();
        assertEquals(DevEmbedListener.MODAL_ID, modal.getId());
        assertEquals(2, modal.getComponents().size());
    }
}
