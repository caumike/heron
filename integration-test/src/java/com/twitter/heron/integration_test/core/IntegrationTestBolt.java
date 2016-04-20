package com.twitter.heron.integration_test.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.twitter.heron.api.bolt.IOutputCollector;
import com.twitter.heron.api.bolt.IRichBolt;
import com.twitter.heron.api.bolt.OutputCollector;
import com.twitter.heron.api.generated.TopologyAPI;
import com.twitter.heron.api.topology.OutputFieldsDeclarer;
import com.twitter.heron.api.topology.TopologyContext;
import com.twitter.heron.api.tuple.Fields;
import com.twitter.heron.api.tuple.Tuple;
import com.twitter.heron.api.tuple.Values;

public class IntegrationTestBolt implements IRichBolt {
  private static final Logger LOG = Logger.getLogger(IntegrationTestBolt.class.getName());
  private final IRichBolt delegateBolt;
  private int terminalsToReceive = 0;
  private long tuplesReceived = 0;
  private long tuplesProcessed = 0;
  // For ack/fail
  private Tuple currentTupleProcessing = null;
  private OutputCollector collector;

  public IntegrationTestBolt(IRichBolt delegate) {
    this.delegateBolt = delegate;
  }


  @Override
  public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
    // Set the # of terminal Signal to receive, = the # number all instance of upstream components
    HashSet<String> upstreamComponents = new HashSet<String>();
    for (TopologyAPI.StreamId streamId : topologyContext.getThisSources().keySet()) {
      upstreamComponents.add(streamId.getComponentName());
    }
    for (String name : upstreamComponents) {
      terminalsToReceive += topologyContext.getComponentTasks(name).size();
    }

    LOG.fine("TerminalsToReceive: " + terminalsToReceive);

    collector = new OutputCollector(new IntegrationTestBoltCollector(outputCollector));
    delegateBolt.prepare(map, topologyContext, collector);
  }

  @Override
  public void execute(Tuple tuple) {
    tuplesReceived++;
    String streamID = tuple.getSourceStreamId();

    LOG.fine("Received a tuple: " + tuple + " ; from: " + streamID);

    if (streamID.equals(Constants.INTEGRATION_TEST_CONTROL_STREAM_ID)) {
      terminalsToReceive--;
      if (terminalsToReceive == 0) {

        // invoke the finishBatch() callback if necessary
        if (IBatchBolt.class.isInstance(delegateBolt)) {
          LOG.fine("Invoke bolt to do finishBatch!");
          ((IBatchBolt) delegateBolt).finishBatch();
        }

        LOG.info("Populating the terminals to downstream");
        collector.emit(Constants.INTEGRATION_TEST_CONTROL_STREAM_ID,
            tuple,
            new Values(Constants.INTEGRATION_TEST_TERMINAL));
      }
    } else {
      currentTupleProcessing = tuple;
      delegateBolt.execute(tuple);
      // We ack only the tuples in user's logic
      collector.ack(tuple);
    }
  }

  @Override
  public void cleanup() {
    delegateBolt.cleanup();
  }

  @Override
  public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
    outputFieldsDeclarer.declareStream(Constants.INTEGRATION_TEST_CONTROL_STREAM_ID,
        new Fields(Constants.INTEGRATION_TEST_TERMINAL));
    delegateBolt.declareOutputFields(outputFieldsDeclarer);
  }

  @Override
  public Map<String, Object> getComponentConfiguration() {
    return delegateBolt.getComponentConfiguration();
  }

  private class IntegrationTestBoltCollector implements IOutputCollector {
    private final IOutputCollector delegate;

    public IntegrationTestBoltCollector(IOutputCollector delegate) {
      this.delegate = delegate;
    }

    @Override
    public List<Integer> emit(String s, Collection<Tuple> tuples, List<Object> objects) {
      if (tuples == null) {
        return delegate.emit(s, Arrays.asList(currentTupleProcessing), objects);
      }
      return delegate.emit(s, tuples, objects);
    }

    @Override
    public void emitDirect(int i, String s, Collection<Tuple> tuples, List<Object> objects) {
      if (tuples == null) {
        delegate.emitDirect(i, s, Arrays.asList(currentTupleProcessing), objects);
      }
      delegate.emitDirect(i, s, tuples, objects);
    }

    @Override
    public void ack(Tuple tuple) {
      LOG.fine("Try to do a ack. tuplesProcessed: "
          + tuplesProcessed + " ; tuplesReceived: " + tuplesReceived);
      if (tuplesProcessed < tuplesReceived) {
        delegate.ack(tuple);
        tuplesProcessed++;
      }
    }

    @Override
    public void fail(Tuple tuple) {
      LOG.fine("Try to do a fail. tuplesProcessed: "
          + tuplesProcessed + " ; tuplesReceived: " + tuplesReceived);
      if (tuplesProcessed < tuplesReceived) {
        delegate.fail(tuple);
        tuplesProcessed++;
      }
    }

    @Override
    public void reportError(Throwable throwable) {
      delegate.reportError(throwable);
    }
  }
}
