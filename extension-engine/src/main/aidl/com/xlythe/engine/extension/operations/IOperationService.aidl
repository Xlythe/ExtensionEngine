package com.xlythe.engine.extension.operations;
import android.os.Bundle;
import com.xlythe.engine.extension.operations.IOperationEvents;
import com.xlythe.engine.extension.operations.IOperationSession;
interface IOperationService {
    IOperationSession open(String operation, IBinder approval, in Bundle arguments, IOperationEvents events);
}
