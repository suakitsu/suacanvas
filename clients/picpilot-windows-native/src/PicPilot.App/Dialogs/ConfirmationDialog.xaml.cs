using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace PicPilot.App.Dialogs;

public partial class ConfirmationDialog : Window
{
    private bool _isClosing;

    public ConfirmationDialog(string title, string description)
    {
        InitializeComponent();
        TitleText.Text = title;
        DescriptionText.Text = description;
    }

    private void Window_Loaded(object sender, RoutedEventArgs e)
    {
        if (Owner is not null) Owner.Opacity = 0.94;
        BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
        if (DialogCard.RenderTransform is TransformGroup group)
        {
            ((ScaleTransform)group.Children[0]).BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(0.97, 1, TimeSpan.FromMilliseconds(190)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
            ((ScaleTransform)group.Children[0]).BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(0.97, 1, TimeSpan.FromMilliseconds(190)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
            ((TranslateTransform)group.Children[1]).BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(8, 0, TimeSpan.FromMilliseconds(190)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
        }
    }

    private void Confirm_Click(object sender, RoutedEventArgs e) => CloseAnimated(true);
    private void Cancel_Click(object sender, RoutedEventArgs e) => CloseAnimated(false);

    private void Window_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key != Key.Escape) return;
        e.Handled = true;
        CloseAnimated(false);
    }

    private void CloseAnimated(bool result)
    {
        if (_isClosing) return;
        _isClosing = true;
        var animation = new DoubleAnimation(Opacity, 0, TimeSpan.FromMilliseconds(110));
        animation.Completed += (_, _) => { DialogResult = result; Close(); };
        BeginAnimation(OpacityProperty, animation);
    }

    private void Window_Closed(object? sender, EventArgs e)
    {
        if (Owner is not null) Owner.Opacity = 1;
    }
}
