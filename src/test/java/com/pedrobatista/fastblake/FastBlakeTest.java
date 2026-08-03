package com.pedrobatista.fastblake;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FastBlakeTest {
    @Test
    public void testHashNotNull() {
        assertNotNull(FastBlake.hash("test"));
    }
}
