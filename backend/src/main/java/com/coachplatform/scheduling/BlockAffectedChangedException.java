package com.coachplatform.scheduling;

import com.coachplatform.common.ApiException;
import com.coachplatform.scheduling.api.BlockPreview;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** The classes the block would release are not the ones the coach confirmed: nothing was saved; the answer carries the current list. */
public class BlockAffectedChangedException extends ApiException {

    BlockAffectedChangedException(BlockPreview preview) {
        super(HttpStatus.CONFLICT, "BLOCK_AFFECTED_CHANGED", Map.of("preview", preview));
    }
}
