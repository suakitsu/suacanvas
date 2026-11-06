using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using PicPilot.App.Services;

namespace PicPilot.App.Converters;

public sealed class BooleanToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) => value is true ? Visibility.Visible : Visibility.Collapsed;
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => value is Visibility.Visible;
}

public sealed class InverseBooleanToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) => value is true ? Visibility.Collapsed : Visibility.Visible;
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => value is not Visibility.Visible;
}

public sealed class InverseBooleanConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) => value is not true;
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => value is not true;
}

public sealed class NullToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is null || value is string text && string.IsNullOrWhiteSpace(text) ? Visibility.Collapsed : Visibility.Visible;
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => System.Windows.Data.Binding.DoNothing;
}

public sealed class StringEqualsToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        string.Equals(value?.ToString(), parameter?.ToString(), StringComparison.OrdinalIgnoreCase) ? Visibility.Visible : Visibility.Collapsed;
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => System.Windows.Data.Binding.DoNothing;
}

public sealed class SelectedPageBrushConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var selected = int.TryParse(value?.ToString(), out var page) && int.TryParse(parameter?.ToString(), out var expected) && page == expected;
        return selected ? new SolidColorBrush(System.Windows.Media.Color.FromRgb(234, 241, 255)) : System.Windows.Media.Brushes.Transparent;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => System.Windows.Data.Binding.DoNothing;
}

public static class MediaDisplaySourceRegistry
{
    public static MediaLibraryService? Current { get; set; }
}

public sealed class MediaDisplayUriConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var text = value?.ToString();
        if (string.IsNullOrWhiteSpace(text) || MediaDisplaySourceRegistry.Current is null) return DependencyProperty.UnsetValue;
        try
        {
            var uri = MediaDisplaySourceRegistry.Current.ResolveDisplayUri(text);
            var image = new BitmapImage();
            image.BeginInit();
            image.UriSource = uri;
            if (uri.IsFile) image.CacheOption = BitmapCacheOption.OnLoad;
            image.EndInit();
            if (image.CanFreeze) image.Freeze();
            return image;
        }
        catch
        {
            return DependencyProperty.UnsetValue;
        }
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => System.Windows.Data.Binding.DoNothing;
}
