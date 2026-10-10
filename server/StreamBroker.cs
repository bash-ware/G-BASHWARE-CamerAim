using System.Collections.Concurrent;
using System.Threading.Channels;

namespace CamerAim.Server;

public sealed class StreamBroker
{
    private readonly ConcurrentDictionary<Guid, Subscription> subscriptions = new();

    public Subscription Subscribe(string machineId)
    {
        var subscription = new Subscription(Guid.NewGuid(), machineId, Remove);
        subscriptions[subscription.Id] = subscription;
        return subscription;
    }

    public void Publish(string machineId, string json)
    {
        foreach (Subscription subscription in subscriptions.Values)
        {
            if (string.Equals(subscription.MachineId, machineId, StringComparison.Ordinal))
                subscription.Writer.TryWrite(json);
        }
    }

    private void Remove(Guid id) => subscriptions.TryRemove(id, out _);

    public sealed class Subscription : IDisposable
    {
        private readonly Action<Guid> remove;
        private readonly Channel<string> channel = Channel.CreateBounded<string>(
            new BoundedChannelOptions(128)
            {
                SingleReader = true,
                SingleWriter = false,
                FullMode = BoundedChannelFullMode.DropOldest
            });
        private int disposed;

        internal Subscription(Guid id, string machineId, Action<Guid> remove)
        {
            Id = id;
            MachineId = machineId;
            this.remove = remove;
        }

        internal Guid Id { get; }
        internal ChannelWriter<string> Writer => channel.Writer;
        public string MachineId { get; }
        public ChannelReader<string> Reader => channel.Reader;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref disposed, 1) != 0) return;
            remove(Id);
            channel.Writer.TryComplete();
        }
    }
}
